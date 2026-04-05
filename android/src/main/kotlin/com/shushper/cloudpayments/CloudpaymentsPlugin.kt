package com.shushper.cloudpayments

import android.app.Activity.RESULT_CANCELED
import android.app.Activity.RESULT_OK
import android.content.Intent
import android.util.Log
import androidx.annotation.NonNull
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wallet.*
import com.shushper.cloudpayments.googlepay.GooglePayUtil
import com.shushper.cloudpayments.sdk.cp_card.CPCard
import com.shushper.cloudpayments.sdk.three_ds.ThreeDSDialogListener
import com.shushper.cloudpayments.sdk.three_ds.ThreeDsDialogFragment
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

const val LOAD_PAYMENT_DATA_REQUEST_CODE = 991

/** CloudpaymentsPlugin */
class CloudpaymentsPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {

    private lateinit var channel: MethodChannel
    private var activity: FlutterFragmentActivity? = null
    private var binding: ActivityPluginBinding? = null
    private var paymentsClient: PaymentsClient? = null
    private var lastPaymentResult: Result? = null

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "cloudpayments")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        this.activity = binding.activity as? FlutterFragmentActivity
        this.binding = binding
        binding.addActivityResultListener { requestCode, resultCode, data -> onActivityResult(requestCode, resultCode, data) }
    }

    override fun onDetachedFromActivity() {
        this.activity = null
        this.paymentsClient = null
        binding?.removeActivityResultListener { requestCode, resultCode, data -> onActivityResult(requestCode, resultCode, data) }
        binding = null
    }

    override fun onDetachedFromActivityForConfigChanges() {
        this.activity = null
        binding?.removeActivityResultListener { requestCode, resultCode, data -> onActivityResult(requestCode, resultCode, data) }
        binding = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        this.activity = binding.activity as? FlutterFragmentActivity
        this.binding = binding
        binding.addActivityResultListener { requestCode, resultCode, data -> onActivityResult(requestCode, resultCode, data) }
    }

    companion object {
        @JvmStatic
        fun registerWith() {
            // пусто — нужен только для старых проектов, Registrar больше не используется
        }
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        when (call.method) {
            "isValidNumber" -> result.success(isValidNumber(call))
            "isValidExpiryDate" -> result.success(isValidExpiryDate(call))
            "cardCryptogram" -> result.success(cardCryptogram(call))
            "show3ds" -> show3ds(call, result)
            "createPaymentsClient" -> createPaymentsClient(call, result)
            "isGooglePayAvailable" -> checkIsGooglePayAvailable(call, result)
            "requestGooglePayPayment" -> requestGooglePayPayment(call, result)
            else -> result.notImplemented()
        }
    }

    private fun isValidNumber(call: MethodCall): Boolean {
        val params = call.arguments as Map<String, Any>
        val cardNumber = params["cardNumber"] as String
        return CPCard.isValidNumber(cardNumber)
    }

    private fun isValidExpiryDate(call: MethodCall): Boolean {
        val params = call.arguments as Map<String, Any>
        val expiryDate = params["expiryDate"] as String
        return CPCard.isValidExpDate(expiryDate)
    }

    private fun cardCryptogram(call: MethodCall): Map<String, Any?> {
        val params = call.arguments as Map<String, Any>
        val card = CPCard(params["cardNumber"] as String, params["cardDate"] as String, params["cardCVC"] as String)
        var cryptogram: String? = null
        var error: String? = null
        try {
            cryptogram = card.cardCryptogram(params["publicId"] as String)
        } catch (e: Exception) {
            e.printStackTrace()
            error = e.javaClass.simpleName
        }
        return mapOf("cryptogram" to cryptogram, "error" to error)
    }

    private fun show3ds(call: MethodCall, result: Result) {
        val params = call.arguments as Map<String, Any>
        activity?.let {
            val dialog = ThreeDsDialogFragment.newInstance(
                params["acsUrl"] as String,
                params["transactionId"] as String,
                params["paReq"] as String
            )
            dialog.show(it.supportFragmentManager, "3DS")
            dialog.setListener(object : ThreeDSDialogListener {
                override fun onAuthorizationCompleted(md: String, paRes: String) {
                    result.success(mapOf("md" to md, "paRes" to paRes))
                }
                override fun onAuthorizationFailed(html: String?) {
                    result.error("AuthorizationFailed", "authorizationFailed", null)
                }
                override fun onCancel() {
                    result.success(null)
                }
            })
        }
    }

    private fun createPaymentsClient(call: MethodCall, result: Result) {
        val params = call.arguments as Map<String, Any>
        val environment = when (params["environment"] as String) {
            "test" -> WalletConstants.ENVIRONMENT_TEST
            "production" -> WalletConstants.ENVIRONMENT_PRODUCTION
            else -> WalletConstants.ENVIRONMENT_TEST
        }
        val activity = activity
        if (activity != null) {
            paymentsClient = GooglePayUtil.createPaymentsClient(activity, environment)
            result.success(null)
        } else {
            result.error("GooglePayError", "Couldn't create Payments Client", null)
        }
    }

    private fun checkIsGooglePayAvailable(call: MethodCall, result: Result) {
        val isReadyToPayJson = GooglePayUtil.isReadyToPayRequest()
        if (isReadyToPayJson == null) {
            result.error("GooglePayError", "Google pay is not available", null)
            return
        }
        val request = IsReadyToPayRequest.fromJson(isReadyToPayJson.toString()) ?: run {
            result.error("GooglePayError", "Google pay is not available", null)
            return
        }
        paymentsClient?.isReadyToPay(request)?.addOnCompleteListener { completedTask ->
            try {
                completedTask.getResult(ApiException::class.java)?.let { available ->
                    result.success(available)
                }
            } catch (exception: ApiException) {
                result.error("GooglePayError", exception.message, null)
            }
        }
    }

    private fun requestGooglePayPayment(call: MethodCall, result: Result) {
        val params = call.arguments as Map<String, Any>
        val paymentDataRequestJson = GooglePayUtil.getPaymentDataRequest(
            params["price"] as String,
            params["currencyCode"] as String,
            params["countryCode"] as String,
            params["merchantName"] as String,
            params["publicId"] as String
        )
        if (paymentDataRequestJson == null) {
            result.error("RequestPayment", "Can't fetch payment data request", null)
            return
        }
        val request = PaymentDataRequest.fromJson(paymentDataRequestJson.toString())
        val paymentsClient = paymentsClient
        val activity = activity
        lastPaymentResult = result
        if (request != null && paymentsClient != null && activity != null) {
            AutoResolveHelper.resolveTask(paymentsClient.loadPaymentData(request), activity, LOAD_PAYMENT_DATA_REQUEST_CODE)
        }
    }

    private fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == LOAD_PAYMENT_DATA_REQUEST_CODE) {
            when (resultCode) {
                RESULT_OK -> onPaymentOk(data)
                RESULT_CANCELED -> onPaymentCanceled()
                AutoResolveHelper.RESULT_ERROR -> onPaymentError(data)
            }
            return true
        }
        return false
    }

    private fun onPaymentOk(data: Intent?) {
        if (data == null) lastPaymentResult?.error("RequestPayment", "Intent is null", null)
        else {
            val paymentData = PaymentData.getFromIntent(data)
            if (paymentData == null) lastPaymentResult?.error("RequestPayment", "Payment data is null", null)
            else lastPaymentResult?.success(mapOf("status" to "SUCCESS", "result" to paymentData.toJson()))
        }
        lastPaymentResult = null
    }

    private fun onPaymentCanceled() {
        lastPaymentResult?.success(mapOf("status" to "CANCELED"))
        lastPaymentResult = null
    }

    private fun onPaymentError(data: Intent?) {
        if (data == null) lastPaymentResult?.error("RequestPayment", "Intent is null", null)
        else {
            val status = AutoResolveHelper.getStatusFromIntent(data)
            if (status == null) lastPaymentResult?.error("RequestPayment", "Status is null", null)
            else lastPaymentResult?.success(
                mapOf(
                    "status" to "ERROR",
                    "error_code" to status.statusCode,
                    "error_message" to status.statusMessage,
                    "error_description" to status.toString()
                )
            )
        }
        lastPaymentResult = null
    }
}