/*
 * Copyright 2021 DataCanvas
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.dingodb.exec.operator;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.dingodb.common.type.DingoType;
import io.dingodb.exec.channel.ReceiveEndpoint;
import io.dingodb.exec.codec.TxRxCodec;
import io.dingodb.exec.codec.TxRxCodecImpl;
import io.dingodb.exec.fin.Fin;
import io.dingodb.exec.fin.FinWithException;
import io.dingodb.exec.fin.FinWithProfiles;
import io.dingodb.exec.fin.OperatorProfile;
import io.dingodb.exec.utils.QueueUtils;
import io.dingodb.exec.utils.TagUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

@Slf4j
@JsonPropertyOrder({"host", "port", "schema", "output"})
@JsonTypeName("receive")
public final class ReceiveOperator extends SourceOperator {
    private static final int QUEUE_CAPACITY = 1024;

    @JsonProperty("host")
    private final String host;
    @JsonProperty("port")
    private final int port;
    @JsonProperty("schema")
    private final DingoType schema;

    private String tag;
    private TxRxCodec codec;
    private BlockingQueue<Object[]> tupleQueue;
    private ReceiveEndpoint endpoint;
    private Fin finObj;

    @JsonCreator
    public ReceiveOperator(
        @JsonProperty("host") String host,
        @JsonProperty("port") int port,
        @JsonProperty("schema") DingoType schema
    ) {
        super();
        this.host = host;
        this.port = port;
        this.schema = schema;
    }

    @Override
    public void init() {
        super.init();
        codec = new TxRxCodecImpl(schema);
        tupleQueue = new LinkedBlockingDeque<>(QUEUE_CAPACITY);
        tag = TagUtils.tag(getTask().getJobId(), getId());
        endpoint = new ReceiveEndpoint(host, port, tag, (byte[] content) -> {
            try {
                List<Object[]> tuples = codec.decode(content);
                for (Object[] tuple : tuples) {
                    if (!endpoint.isStopped() || tuple[0] instanceof Fin) {
                        QueueUtils.forcePut(tupleQueue, tuple);
                    }
                }
            } catch (IOException e) {
                log.error("Exception in receive handler:", e);
            }
        });
        endpoint.init();
        if (log.isDebugEnabled()) {
            log.debug("ReceiveOperator initialized with host={} port={} tag={}", host, port, tag);
        }
    }

    @Override
    public void fin(int pin, Fin fin) {
        /*
          when the upstream operator('sender') has failed,
          then the current operator('receiver') should fail too
          so the `Fin` should use FinWithException
         */
        if (finObj != null && finObj instanceof FinWithException) {
            super.fin(pin, finObj);
        } else {
            super.fin(pin, fin);
        }
    }

    @Override
    public boolean push() {
        long count = 0;
        OperatorProfile profile = getProfile();
        profile.setStartTimeStamp(System.currentTimeMillis());
        while (true) {
            Object[] tuple = QueueUtils.forceTake(tupleQueue);
            if (!(tuple[0] instanceof Fin)) {
                ++count;
                if (log.isDebugEnabled()) {
                    log.debug("(tag = {}) Take out tuple {} from receiving queue.", tag, schema.format(tuple));
                }
                if (!output.push(tuple)) {
                    endpoint.stop();
                    // Stay in loop to receive FIN.
                }
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("(tag = {}) Take out FIN.", tag);
                }
                profile.setEndTimeStamp(System.currentTimeMillis());
                profile.setProcessedTupleCount(count);
                Fin fin = (Fin) tuple[0];
                if (fin instanceof FinWithProfiles) {
                    profiles.addAll(((FinWithProfiles) fin).getProfiles());
                } else if (fin instanceof FinWithException) {
                    finObj = fin;
                }
                break;
            }
        }
        return false;
    }

    @Override
    public void destroy() {
        safeCloseEndpoint();
    }

    private void safeCloseEndpoint() {
        if (endpoint != null) {
            endpoint.close();
        }
    }
}
