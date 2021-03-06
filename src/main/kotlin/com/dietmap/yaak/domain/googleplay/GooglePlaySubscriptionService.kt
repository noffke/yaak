package com.dietmap.yaak.domain.googleplay

import com.dietmap.yaak.api.googleplay.GooglePlayNotificationType.SUBSCRIPTION_PURCHASED
import com.dietmap.yaak.api.googleplay.GooglePlayNotificationType.SUBSCRIPTION_RENEWED
import com.dietmap.yaak.api.googleplay.GooglePlaySubscriptionNotification
import com.dietmap.yaak.api.googleplay.PubSubDeveloperNotification
import com.dietmap.yaak.api.googleplay.PurchaseRequest
import com.dietmap.yaak.domain.checkArgument
import com.dietmap.yaak.domain.userapp.AppMarketplace
import com.dietmap.yaak.domain.userapp.NotificationType
import com.dietmap.yaak.domain.userapp.UserAppClient
import com.dietmap.yaak.domain.userapp.UserAppSubscriptionNotification
import com.google.api.services.androidpublisher.AndroidPublisher
import com.google.api.services.androidpublisher.model.SubscriptionPurchase
import com.google.api.services.androidpublisher.model.SubscriptionPurchasesAcknowledgeRequest
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.stereotype.Service
import java.math.BigDecimal


@ConditionalOnBean(AndroidPublisher::class)
@Service
class GooglePlaySubscriptionService(val androidPublisherApiClient: AndroidPublisher, val userAppClient: UserAppClient) {
    private val PAYMENT_RECEIVED_CODE = 1
    private val PAYMENT_FREE_TRIAL_CODE = 2
    private val logger = KotlinLogging.logger { }

    fun handlePurchase(purchaseRequest: PurchaseRequest, initalBuy: Boolean = true): SubscriptionPurchase? {
        val subscription = androidPublisherApiClient.Purchases().Subscriptions().get(purchaseRequest.packageName, purchaseRequest.subscriptionId, purchaseRequest.purchaseToken).execute()
        checkArgument(subscription.paymentState in listOf(PAYMENT_RECEIVED_CODE, PAYMENT_FREE_TRIAL_CODE)) { "Subscription has not been paid yet, paymentState=${subscription.paymentState}" }
        val notificationResponse = userAppClient.sendSubscriptionNotification(UserAppSubscriptionNotification(
                notificationType = if (initalBuy) NotificationType.SUBSCRIPTION_PURCHASED else NotificationType.SUBSCRIPTION_RENEWED,
                appMarketplace = AppMarketplace.GOOGLE_PLAY,
                countryCode = subscription.countryCode,
                price = BigDecimal(subscription.priceAmountMicros).divide(BigDecimal(1000 * 1000)),
                currencyCode = subscription.priceCurrencyCode,
                transactionId = subscription.orderId,
                originalTransactionId = toInitialOrderId(subscription.orderId),
                productId = purchaseRequest.subscriptionId,
                description = "Google Play ${if (initalBuy) "initial" else "renewal"} subscription order",
                orderingUserId = purchaseRequest.orderingUserId,
                discountCode = purchaseRequest.discountCode,
                expiryTimeMillis = subscription.expiryTimeMillis
        ))

        checkArgument(notificationResponse != null) { "Could not create subscription order ${subscription.orderId} in user app" }

        if (subscription.acknowledgementState == 0) {
            logger.info { "Acknowledging Google Play subscription purchase of id=${subscription.orderId}, purchaseToken=${purchaseRequest.purchaseToken}" }
            val content = SubscriptionPurchasesAcknowledgeRequest().setDeveloperPayload("{ applicationOrderId: ${notificationResponse?.orderId}, orderingUserId: ${purchaseRequest.orderingUserId} }")
            androidPublisherApiClient.Purchases().Subscriptions().acknowledge(purchaseRequest.packageName, purchaseRequest.subscriptionId, purchaseRequest.purchaseToken, content).execute()
        }
        return subscription;
    }

    private fun toInitialOrderId(orderId: String?): String {
        return if (orderId != null) {
            val split = orderId.split("..")
            return "${split[0]}..0"
        } else ""
    }

    fun handleSubscriptionNotification(pubsubNotification: PubSubDeveloperNotification) {
        pubsubNotification.subscriptionNotification?.let {
            logger.info { "Handling PubSub notification of type: ${it.notificationType}" }
            try {
                when (it.notificationType) {
                    SUBSCRIPTION_PURCHASED -> handlePurchase(PurchaseRequest(pubsubNotification.packageName, it.subscriptionId, it.purchaseToken))
                    SUBSCRIPTION_RENEWED -> handlePurchase(PurchaseRequest(pubsubNotification.packageName, it.subscriptionId, it.purchaseToken),false)
                    else -> handleStatusUpdate(pubsubNotification.packageName, it)
                }
            } catch (e: Exception) {
                logger.error(e) { "Error handling PubSub notification" }
                throw e
            }
        }
    }

    private fun handleStatusUpdate(packageName: String, notification: GooglePlaySubscriptionNotification) {
        val subscription = androidPublisherApiClient.Purchases().Subscriptions().get(packageName, notification.subscriptionId, notification.purchaseToken).execute()
        logger.debug { "Google Play subscription details: $subscription" }
        subscription.cancelReason?.let { logger.info { "Subscription cancel reason: $it" } }
        subscription.cancelSurveyResult?.let { logger.info { "Subscription cancel survey result: $it" } }
        val subscriptionUpdate = UserAppSubscriptionNotification(
                notificationType = NotificationType.valueOf(notification.notificationType.name),
                description = "Google Play subscription update: " + notification.notificationType,
                productId = notification.subscriptionId,
                countryCode = subscription.countryCode,
                price = BigDecimal(subscription.priceAmountMicros).divide(BigDecimal(1000 * 1000)),
                currencyCode = subscription.priceCurrencyCode,
                transactionId = subscription.orderId,
                originalTransactionId = toInitialOrderId(subscription.orderId),
                appMarketplace = AppMarketplace.GOOGLE_PLAY,
                expiryTimeMillis = subscription.expiryTimeMillis
        )
        userAppClient.sendSubscriptionNotification(subscriptionUpdate)
        logger.info { "Google Play subscription notification has been sent to user app: $subscriptionUpdate" }
    }
}