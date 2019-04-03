/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.daomonitor.metrics.p2p;

import bisq.core.dao.node.full.RawBlock;
import bisq.core.dao.node.messages.GetBlocksRequest;
import bisq.core.dao.node.messages.GetBlocksResponse;

import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.network.CloseConnectionReason;
import bisq.network.p2p.network.Connection;
import bisq.network.p2p.network.MessageListener;
import bisq.network.p2p.network.NetworkNode;

import bisq.common.Timer;
import bisq.common.UserThread;
import bisq.common.app.DevEnv;
import bisq.common.proto.network.NetworkEnvelope;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.NotNull;



import bisq.daomonitor.metrics.DaoMetrics;

@Slf4j
class DaoMonitorBlockRequestHandler implements MessageListener {
    private static final long TIMEOUT = 120;
    private NodeAddress peersNodeAddress;
    private long requestTs;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Listener
    ///////////////////////////////////////////////////////////////////////////////////////////

    public interface Listener {
        void onComplete();

        @SuppressWarnings("UnusedParameters")
        void onFault(String errorMessage, NodeAddress nodeAddress);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Class fields
    ///////////////////////////////////////////////////////////////////////////////////////////

    private final NetworkNode networkNode;
    private final DaoMetrics daoMetrics;
    private final Listener listener;
    private Timer timeoutTimer;
    private final int nonce = new Random().nextInt();
    private boolean stopped;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public DaoMonitorBlockRequestHandler(NetworkNode networkNode, DaoMetrics daoMetrics, Listener listener) {
        this.networkNode = networkNode;
        this.daoMetrics = daoMetrics;
        this.listener = listener;
    }

    public void cancel() {
        cleanup();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void requestData(NodeAddress nodeAddress) {
        peersNodeAddress = nodeAddress;
        requestTs = new Date().getTime();
        if (!stopped) {

            GetBlocksRequest getBlocksRequest = new GetBlocksRequest(0, nonce);
            daoMetrics.setLastDataRequestTs(System.currentTimeMillis());

            if (timeoutTimer != null) {
                log.warn("timeoutTimer was already set. That must not happen.");
                timeoutTimer.stop();

                if (DevEnv.isDevMode())
                    throw new RuntimeException("timeoutTimer was already set. That must not happen.");
            }
            timeoutTimer = UserThread.runAfter(() -> {  // setup before sending to avoid race conditions
                        if (!stopped) {
                            String errorMessage = "A timeout occurred at sending getBlocksRequest:" + getBlocksRequest +
                                    " on nodeAddress:" + nodeAddress;
                            log.warn(errorMessage + " / DaoMonitorBlockRequestHandler=" + DaoMonitorBlockRequestHandler.this);
                            handleFault(errorMessage, nodeAddress, CloseConnectionReason.SEND_MSG_TIMEOUT);
                        } else {
                            log.trace("We have stopped already. We ignore that timeoutTimer.run call. " +
                                    "Might be caused by an previous networkNode.sendMessage.onFailure.");
                        }
                    },
                    TIMEOUT);

            log.info("We send a getBlocksRequest to peer {}. ", nodeAddress);
            networkNode.addMessageListener(this);
            SettableFuture<Connection> future = networkNode.sendMessage(nodeAddress, getBlocksRequest);
            Futures.addCallback(future, new FutureCallback<>() {
                @Override
                public void onSuccess(Connection connection) {
                    if (!stopped) {
                        log.info("Send getBlocksRequest to " + nodeAddress + " has succeeded.");
                    } else {
                        log.trace("We have stopped already. We ignore that networkNode.sendMessage.onSuccess call." +
                                "Might be caused by an previous timeout.");
                    }
                }

                @Override
                public void onFailure(@NotNull Throwable throwable) {
                    if (!stopped) {
                        String errorMessage = "Sending getBlocksRequest to " + nodeAddress +
                                " failed.\n\t" +
                                "getBlocksRequest=" + getBlocksRequest + "." +
                                "\n\tException=" + throwable.getMessage();
                        log.warn(errorMessage);
                        handleFault(errorMessage, nodeAddress, CloseConnectionReason.SEND_MSG_FAILURE);
                    } else {
                        log.trace("We have stopped already. We ignore that networkNode.sendMessage.onFailure call. " +
                                "Might be caused by an previous timeout.");
                    }
                }
            });
        } else {
            log.warn("We have stopped already. We ignore that getBlocksRequest call.");
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // MessageListener implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onMessage(NetworkEnvelope networkEnvelop, Connection connection) {
        if (networkEnvelop instanceof GetBlocksResponse &&
                connection.getPeersNodeAddressOptional().isPresent() &&
                connection.getPeersNodeAddressOptional().get().equals(peersNodeAddress)) {
            if (!stopped) {
                GetBlocksResponse getBlocksResponse = (GetBlocksResponse) networkEnvelop;
                if (getBlocksResponse.getRequestNonce() == nonce) {
                    stopTimeoutTimer();

                    List<RawBlock> blocks = getBlocksResponse.getBlocks();
                    int numBlocks = blocks.size();

                    // Log different data types
                    StringBuilder sb = new StringBuilder();
                    sb.append("\n#################################################################\n");
                    sb.append("Connected to node: ").append(peersNodeAddress.getFullAddress()).append("\n");
                    sb.append("Received ").append(numBlocks).append(" blocks\n");
                    sb.append("#################################################################");
                    log.info(sb.toString());

                    HashMap<String, Integer> receivedObjects = new HashMap<>();
                    receivedObjects.put("BSQ Blocks", numBlocks);
                    daoMetrics.getReceivedObjectsList().add(receivedObjects);

                    final long duration = new Date().getTime() - requestTs;
                    log.info("Requesting data took {} ms", duration);
                    daoMetrics.getRequestDurations().add(duration);
                    daoMetrics.setLastDataResponseTs(System.currentTimeMillis());

                    cleanup();
                    listener.onComplete();
                    // connection.shutDown(CloseConnectionReason.CLOSE_REQUESTED_BY_PEER, listener::onComplete);
                } else {
                    log.debug("Nonce not matching. That can happen rarely if we get a response after a canceled " +
                                    "handshake (timeout causes connection close but peer might have sent a msg before " +
                                    "connection was closed).\n\t" +
                                    "We drop that message. nonce={} / requestNonce={}",
                            nonce, getBlocksResponse.getRequestNonce());
                }
            } else {
                log.warn("We have stopped already. We ignore that onDataRequest call.");
            }
        }
    }

    public void stop() {
        cleanup();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////


    private void handleFault(String errorMessage, NodeAddress nodeAddress, CloseConnectionReason closeConnectionReason) {
        cleanup();
        // We do not log every error only if it fails several times in a row.

        // We do not close the connection as it might be we have opened a new connection for that peer and
        // we don't want to close that. We do not know the connection at fault as the fault handler does not contain that,
        // so we could only search for connections for that nodeAddress but that would close an new connection attempt.
        listener.onFault(errorMessage, nodeAddress);
    }

    private void cleanup() {
        stopped = true;
        networkNode.removeMessageListener(this);
        stopTimeoutTimer();
    }

    private void stopTimeoutTimer() {
        if (timeoutTimer != null) {
            timeoutTimer.stop();
            timeoutTimer = null;
        }
    }
}
