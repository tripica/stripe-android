package com.stripe.example.activity

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.liveData
import com.stripe.android.paymentsheet.PaymentSheetFlowController
import com.stripe.example.databinding.ActivityPaymentSheetInlineBinding
import com.stripe.example.module.StripeIntentViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class LaunchPaymentSheetCustomActivity : AppCompatActivity() {
    private val viewBinding by lazy {
        ActivityPaymentSheetInlineBinding.inflate(layoutInflater)
    }

    private val viewModel by lazy {
        ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory(application)
        )[ViewModel::class.java]
    }

    private lateinit var ephemeralKey: EphemeralKey

    private var paymentSheetFlowController: PaymentSheetFlowController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)

        viewModel.inProgress.observe(this) {
            viewBinding.progressBar.visibility = if (it) View.VISIBLE else View.INVISIBLE
            viewBinding.launchInline.isEnabled = !it
            viewBinding.clear.isEnabled = !it
        }
        viewModel.status.observe(this) {
            viewBinding.status.text = it
        }

        viewBinding.clear.setOnClickListener {
            viewModel.clearKeys(this)
            fetchEphemeralKey()
        }
        viewBinding.launchInline.setOnClickListener {
            viewModel.createPaymentIntent(
                "us",
                ephemeralKey.customer
            ).observe(this) { responseResult ->
                responseResult.fold(
                    onSuccess = { json ->
                        viewModel.inProgress.postValue(false)
                        val secret = json.getString("secret")

                        PaymentSheetFlowController.create(
                            this,
                            clientSecret = secret,
                            ephemeralKey = ephemeralKey.key,
                            customerId = ephemeralKey.customer
                        ) {
                            onPaymentSheetFlowControllerResult(it)
                        }
                    },
                    onFailure = {
                        viewModel.status.postValue("${viewModel.status.value}\nFailed: ${it.message}")
                    }
                )
            }
        }
        fetchEphemeralKey()
    }

    private fun onPaymentSheetFlowControllerResult(
        result: PaymentSheetFlowController.Result
    ) {
        when (result) {
            is PaymentSheetFlowController.Result.Success -> {
                this.paymentSheetFlowController = result.paymentSheetFlowController

                this.paymentSheetFlowController?.presentPaymentOptions(this) {
                }
            }
            is PaymentSheetFlowController.Result.Failure -> {
                viewModel.status.postValue(
                    "Failed to create PaymentSheetFlowController: ${result.error.message}"
                )
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        val paymentOption = paymentSheetFlowController?.onPaymentOptionResult(data)
        viewModel.status.value = viewModel.status.value + "\n\nPayment Option: $paymentOption"
    }

    private fun fetchEphemeralKey() {
        viewModel.fetchEphemeralKey(this)
            .observe(this) {
                ephemeralKey = it
            }
    }

    internal data class EphemeralKey(val key: String, val customer: String)

    internal class ViewModel(application: Application) : StripeIntentViewModel(application) {

        fun clearKeys(activity: Activity) {
            val prefs = activity.getPreferences(Context.MODE_PRIVATE)
            CoroutineScope(workContext).launch {
                prefs.edit()
                    .clear()
                    .apply()
            }
        }

        fun fetchEphemeralKey(activity: Activity) = liveData(workContext) {
            val prefs = activity.getPreferences(Context.MODE_PRIVATE)
            val ek = prefs.getString(PREF_EK, null)
            val customer = prefs.getString(PREF_CUSTOMER, null)
            if (ek != null && customer != null) {
                emit(EphemeralKey(ek, customer))
                return@liveData
            }

            inProgress.postValue(true)
            status.postValue("Fetching ephemeral key")
            val responseJson =
                kotlin.runCatching {
                    backendApi
                        .createEphemeralKey(hashMapOf("api_version" to "2020-03-02"))
                        .string()
                }
            responseJson.fold(
                onSuccess = {
                    // TOOD: create separate endpoint that only sends necessary info
                    val json = JSONObject(it)
                    val secret = json.getString("secret")
                    val associatedObjectArray = json.getJSONArray("associated_objects")
                    val typeObject = associatedObjectArray.getJSONObject(0)
                    val objectId = typeObject.getString("id")

                    status.postValue(status.value + "\n\nFetched key $secret for customer $objectId")

                    prefs.edit()
                        .putString(PREF_EK, secret)
                        .putString(PREF_CUSTOMER, objectId)
                        .apply()

                    withContext(Dispatchers.Main) {
                        emit(EphemeralKey(secret, objectId))
                    }
                },
                onFailure = {
                    status.postValue(status.value + "\n\nFetching ephemeral key failed\n${it.message}")
                }
            )
            inProgress.postValue(false)
        }

        private companion object {
            private const val PREF_EK = "pref_ek"
            private const val PREF_CUSTOMER = "pref_customer"
        }
    }
}
