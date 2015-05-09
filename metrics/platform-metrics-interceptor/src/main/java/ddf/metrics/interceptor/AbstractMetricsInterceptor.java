/**
 * Copyright (c) Codice Foundation
 * 
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 * 
 **/
package ddf.metrics.interceptor;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.JmxReporter;
import com.codahale.metrics.MetricRegistry;
import org.apache.commons.collections4.MapUtils;
import org.apache.cxf.message.Exchange;
import org.apache.cxf.message.Message;
import org.apache.cxf.phase.AbstractPhaseInterceptor;
import org.elasticsearch.metrics.ElasticsearchReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * This class is extended by the metrics interceptors used for capturing round trip message latency.
 * 
 * @author willisod
 * 
 */
public abstract class AbstractMetricsInterceptor extends AbstractPhaseInterceptor<Message> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractMetricsInterceptor.class);

    private static final String REGISTRY_NAME = "ddf.metrics.services";

    private static final String HISTOGRAM_NAME = "Latency";

    private static final MetricRegistry metrics = new MetricRegistry();

    private static final JmxReporter reporter = JmxReporter.forRegistry(metrics)
            .inDomain(REGISTRY_NAME).build();

    final Histogram messageLatency;

    private ElasticsearchReporter esReporter;

    /**
     * Constructor to pass the phase to {@code AbstractPhaseInterceptor} and creates a new
     * histogram.
     * 
     * @param phase
     */
    public AbstractMetricsInterceptor(String phase) {
        super(phase);

        messageLatency = metrics.histogram(MetricRegistry.name(HISTOGRAM_NAME));

        reporter.start();

        try {
            LOGGER.info("Starting CXF ES reporter");
            // TODO make Elaticsearch location configurable
            esReporter = ElasticsearchReporter.forRegistry(metrics)
                    .hosts("localhost:9200").build();
            esReporter.start(60, TimeUnit.SECONDS);
        } catch (IOException e) {
            LOGGER.error("Unable to create ES metrics reporter for CXF latency", e);
        }
    }

    protected boolean isClient(Message msg) {
        return msg == null ? false : Boolean.TRUE.equals(msg.get(Message.REQUESTOR_ROLE));
    }

    protected void beginHandlingMessage(Exchange ex) {

        if (null == ex) {
            return;
        }

        LatencyTimeRecorder ltr = ex.get(LatencyTimeRecorder.class);

        if (null != ltr) {
            ltr.beginHandling();
        } else {
            ltr = new LatencyTimeRecorder();
            ex.put(LatencyTimeRecorder.class, ltr);
            ltr.beginHandling();
        }
    }

    protected void endHandlingMessage(Exchange ex) {

        if (null == ex) {
            return;
        }

        LatencyTimeRecorder ltr = ex.get(LatencyTimeRecorder.class);

        if (null != ltr) {
            ltr.endHandling();
            increaseCounter(ex, ltr);

            recordMetrics(ex);
        } else {
            LOGGER.info("can't get the MessageHandling Info");
        }
    }

    private void recordMetrics(Exchange ex) {
        try {
            String address = "";
            String userAgent = "";
            String method = "";
            String queryString = "";
            String uri = "";
            String statusCode = "";

            if (ex.getInMessage() != null) {
                Message in = ex.getInMessage();

                Object request = MapUtils.getObject(in, "HTTP.REQUEST");
                if (request != null && request instanceof HttpServletRequest) {
                    address = ((HttpServletRequest) request).getRemoteAddr();
                }

                userAgent = MapUtils.getString((Map<? super String, ?>) MapUtils.getMap(in, "org.apache.cxf.message.Message.PROTOCOL_HEADERS"), "User-Agent");

                method = MapUtils.getString(in, "org.apache.cxf.request.method");

                queryString = MapUtils.getString(in, "org.apache.cxf.message.Message.QUERY_STRING");

                uri = MapUtils.getString(in, "org.apache.cxf.request.uri");
            }

            if (ex.getOutMessage() != null) {
                Message out = ex.getOutMessage();

                statusCode = MapUtils.getString(out, "org.apache.cxf.message.Message.RESPONSE_CODE");
            }

            // TODO add to new ES index instead of logging
            if (address != null || method != null || uri != null || queryString != null || statusCode != null || userAgent != null) {
                LOGGER.info("{} {} {} {} {} {}", address, method, uri, queryString, statusCode, userAgent);
            }
        } catch (Exception e) {
            // TODO add proper error handling
        }
    }

    private void increaseCounter(Exchange ex, LatencyTimeRecorder ltr) {
        messageLatency.update(ltr.getLatencyTime());
    }

}
