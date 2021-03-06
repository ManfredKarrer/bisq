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

package bisq.core.trade.protocol.tasks.seller;

import bisq.core.trade.Trade;
import bisq.core.trade.messages.PayoutTxPublishedMessage;
import bisq.core.trade.protocol.tasks.TradeTask;

import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.SendMailboxMessageListener;

import bisq.common.taskrunner.TaskRunner;

import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SellerSendPayoutTxPublishedMessage extends TradeTask {
    @SuppressWarnings({"WeakerAccess", "unused"})
    public SellerSendPayoutTxPublishedMessage(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            if (trade.getPayoutTx() != null) {
                final String id = processModel.getOfferId();
                final PayoutTxPublishedMessage message = new PayoutTxPublishedMessage(
                        id,
                        trade.getPayoutTx().bitcoinSerialize(),
                        processModel.getMyNodeAddress(),
                        UUID.randomUUID().toString()
                );
                trade.setState(Trade.State.SELLER_SENT_PAYOUT_TX_PUBLISHED_MSG);
                NodeAddress peersNodeAddress = trade.getTradingPeerNodeAddress();
                log.info("Send {} to peer {}. tradeId={}, uid={}",
                        message.getClass().getSimpleName(), peersNodeAddress, message.getTradeId(), message.getUid());

                processModel.getP2PService().sendEncryptedMailboxMessage(
                        peersNodeAddress,
                        processModel.getTradingPeer().getPubKeyRing(),
                        message,
                        new SendMailboxMessageListener() {
                            @Override
                            public void onArrived() {
                                log.info("{} arrived at peer {}. tradeId={}, uid={}",
                                        message.getClass().getSimpleName(), peersNodeAddress, message.getTradeId(), message.getUid());
                                trade.setState(Trade.State.SELLER_SAW_ARRIVED_PAYOUT_TX_PUBLISHED_MSG);
                                complete();
                            }

                            @Override
                            public void onStoredInMailbox() {
                                log.info("{} stored in mailbox for peer {}. tradeId={}, uid={}",
                                        message.getClass().getSimpleName(), peersNodeAddress, message.getTradeId(), message.getUid());
                                trade.setState(Trade.State.SELLER_STORED_IN_MAILBOX_PAYOUT_TX_PUBLISHED_MSG);
                                complete();
                            }

                            @Override
                            public void onFault(String errorMessage) {
                                log.error("{} failed: Peer {}. tradeId={}, uid={}, errorMessage={}",
                                        message.getClass().getSimpleName(), peersNodeAddress, message.getTradeId(), message.getUid(), errorMessage);
                                trade.setState(Trade.State.SELLER_SEND_FAILED_PAYOUT_TX_PUBLISHED_MSG);
                                appendToErrorMessage("Sending message failed: message=" + message + "\nerrorMessage=" + errorMessage);
                                failed(errorMessage);
                            }
                        }
                );
            } else {
                log.error("trade.getPayoutTx() = " + trade.getPayoutTx());
                failed("PayoutTx is null");
            }
        } catch (Throwable t) {
            failed(t);
        }
    }
}
