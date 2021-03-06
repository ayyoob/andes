/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */


package org.wso2.andes.kernel;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.andes.subscription.LocalSubscription;
import org.wso2.andes.subscription.SubscriptionEngine;

import java.util.*;

/**
 * This class implements topic message delivery. Topic message delivery to multiple subscriptions will be controlled
 * to the slowest subscriber (acknowledge rate) message consuming rate.
 */
public class SlowestSubscriberTopicMessageDeliveryImpl implements MessageDeliveryStrategy {

    private static Log log = LogFactory.getLog(SlowestSubscriberTopicMessageDeliveryImpl.class);
    private SubscriptionEngine subscriptionEngine;

    public SlowestSubscriberTopicMessageDeliveryImpl(SubscriptionEngine subscriptionEngine) {
        this.subscriptionEngine = subscriptionEngine;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int deliverMessageToSubscriptions(MessageDeliveryInfo messageDeliveryInfo) throws AndesException {

        Set<DeliverableAndesMetadata> messages = messageDeliveryInfo.getReadButUndeliveredMessages();
        int sentMessageCount = 0;
        Iterator<DeliverableAndesMetadata> iterator = messages.iterator();
        List<DeliverableAndesMetadata> droppedTopicMessagesList = new ArrayList<>();


        while (iterator.hasNext()) {

            try {
                DeliverableAndesMetadata message = iterator.next();


                /**
                 * get all relevant type of subscriptions. This call does NOT
                 * return hierarchical subscriptions for the destination. There
                 * are duplicated messages for each different subscribed destination.
                 * For durable topic subscriptions this should return queue subscription
                 * bound to unique queue based on subscription id
                 */
                Collection<LocalSubscription> subscriptions4Queue =
                        subscriptionEngine.getActiveLocalSubscribers(message.getDestination(),
                                AndesUtils.getProtocolTypeForMetaDataType(message.getMetaDataType()),
                                messageDeliveryInfo.getDestinationType());

                //All subscription filtering logic for topics goes here
                Iterator<LocalSubscription> subscriptionIterator = subscriptions4Queue.iterator();

                while (subscriptionIterator.hasNext()) {

                    LocalSubscription subscription = subscriptionIterator.next();

                    /*
                     * If this is a topic message, remove all durable topic subscriptions here
                     * because durable topic subscriptions will get messages via queue path.
                     * Also need to consider the arrival time of the message. Only topic
                     * subscribers which appeared before publishing this message should receive it
                     */
                    if (subscription.isDurable() || (subscription.getSubscribeTime() > message.getArrivalTime())) {
                        subscriptionIterator.remove();
                    }

                    // Avoid sending if the selector of subscriber does not match
                    if(!subscription.isMessageAcceptedBySelector(message)) {
                        subscriptionIterator.remove();
                    }
                }

                if (subscriptions4Queue.size() == 0) {
                    iterator.remove(); // remove buffer
                    droppedTopicMessagesList.add(message);

                    continue; // skip this iteration if no subscriptions for the message
                }


                /**
                 * For normal non-durable topic we pre evaluate room for all subscribers and if all subs has room
                 * to accept messages we send them. This means we operate to the speed of slowest subscriber (to
                 * prevent OOM). If it is too slow to make others fast, make that topic subscriber a durable
                 * topic subscriber.
                 */
                boolean allTopicSubscriptionsHasRoom = true;
                for (LocalSubscription subscription : subscriptions4Queue) {
                    if (!subscription.hasRoomToAcceptMessages()) {
                        allTopicSubscriptionsHasRoom = false;
                        break;
                    }
                }
                if (allTopicSubscriptionsHasRoom) {

                    message.markAsScheduledToDeliver(subscriptions4Queue);

                    //schedule message to all subscribers
                    for (LocalSubscription localSubscription : subscriptions4Queue) {
                        MessageFlusher.getInstance().deliverMessageAsynchronously(localSubscription, message);
                    }
                    iterator.remove();
                    if (log.isDebugEnabled()) {
                        log.debug("Removing Scheduled to send message from buffer. MsgId= " + message.getMessageID());
                    }
                    sentMessageCount++;
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("Some subscriptions for destination " + messageDeliveryInfo.getDestination()
                                + " have max unacked messages " + message.getDestination());
                    }
                    //if we continue message order will break
                    break;
                }


            } catch (NoSuchElementException ex) {
                // This exception can occur because the iterator of ConcurrentSkipListSet loads the at-the-time snapshot.
                // Some records could be deleted by the time the iterator reaches them.
                // However, this can only happen at the tail of the collection, not in middle, and it would cause the loop
                // to blindly check for a batch of deleted records.
                // Given this situation, this loop should break so the sendFlusher can re-trigger it.
                // for tracing purposes can use this : log.warn("NoSuchElementException thrown",ex);
                log.warn("NoSuchElementException thrown. ", ex);
                break;
            }
        }

        /**
         * Here we do not need to have orphaned slot scenario (return slot). If there are no subscribers
         * slot will be consumed and metadata will be removed. We duplicate topic messages per node
         */


        /**
         * delete topic messages that were dropped due to no subscriptions
         * for the message and due to has no room to enqueue the message. Delete
         * call is blocking and then slot message count is dropped in order
         */
        MessagingEngine.getInstance().deleteMessages(droppedTopicMessagesList);

        return sentMessageCount;
    }


}
