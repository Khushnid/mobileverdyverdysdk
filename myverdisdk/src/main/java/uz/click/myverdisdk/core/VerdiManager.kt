package uz.click.myverdisdk.core

import android.os.Build
import android.os.Handler
import android.util.Log
import com.squareup.moshi.Moshi
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import uz.click.myverdisdk.core.callbacks.ResponseListener
import uz.click.myverdisdk.core.errors.AppIdEmptyException
import uz.click.myverdisdk.core.errors.ServerNotAvailableException
import uz.click.myverdisdk.core.errors.VerdiNotInitializedException
import uz.click.myverdisdk.model.info.ModelServiceInfo
import uz.click.myverdisdk.model.info.ServiceInfo
import uz.click.myverdisdk.model.request.*
import uz.click.myverdisdk.model.response.AppIdResponse
import uz.click.myverdisdk.util.ErrorUtils
import uz.click.myverdisdk.util.md5
import uz.click.myverdisdk.util.toBase64
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit

class VerdiManager(private var applicationHandler: Handler) {

    companion object {
        var logs = true
        private const val CONNECT_TIME_OUT: Long = 10 * 1000 // 10 second
        private const val READ_TIME_OUT: Long = 10 * 1000 // 10 second
        private const val WRITE_TIME_OUT: Long = 10 * 1000 // 10 second
        private val JSON = MediaType.parse("application/json; charset=utf-8")

        const val LANGUAGE = "ru"
        const val VERDI_BASE_URL = "https://api.digid.uz:8080/"
        const val VERDI_BASE_URL_TEST = "https://testapi.digid.uz:8082/"
        const val APP_ID_VERDI = "3f4b68e09a319cf4"
        const val APP_ID_CLICK = "P8g13lFKmXo8TlFO"
        const val REQUEST_CHECK_APP_ID = "mobile/data/${LANGUAGE}/checkAppId"
        const val REQUEST_SEND_PHONE = "digid-service/phone/${LANGUAGE}/send"
        const val REQUEST_CHECK_PHONE = "digid-service/phone/${LANGUAGE}/check"
        const val REQUEST_REGISTRATION = "pinpp/${LANGUAGE}/registration"
        const val REQUEST_VERIFICATION = "pinpp/${LANGUAGE}/verification"

        const val AUTH_CHECK_APP_ID = "Basic dGVzdHJlYWQ6dGVzdHBhc3M="
        const val AUTH_PHONE = "Basic ZGlnaWQ6ZGlnaWQyMDE5"
        const val AUTH_PINPP = "Basic aXBvdGVrYV9tb2JpbGU6UmxtX2lwb3Rla2E="
    }

    private var okClient: OkHttpClient
    private var moshi = Moshi.Builder().build()
    var invoiceCancelled = false

    init {
        val dispatcher = Dispatcher()
        dispatcher.maxRequests = 1
        val okhttpClientBuilder = OkHttpClient.Builder()
        okhttpClientBuilder.dispatcher(dispatcher)
        okhttpClientBuilder.addInterceptor(loggingInterceptor())
        okhttpClientBuilder
            .connectTimeout(CONNECT_TIME_OUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIME_OUT, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIME_OUT, TimeUnit.SECONDS)
        okClient = okhttpClientBuilder.build()
    }

    private fun loggingInterceptor(): Interceptor {
        val logging = HttpLoggingInterceptor()
        if (logs)
            logging.level = HttpLoggingInterceptor.Level.BODY
        else
            logging.level = HttpLoggingInterceptor.Level.NONE
        return logging
    }

    private fun checkAppId(listener: ResponseListener<Any>) {
        if (Verdi.config.appId.isEmpty()) {
            listener.onFailure(AppIdEmptyException())
            return
        }
        val appIdRequest = AppIdRequest(
            Verdi.config.appId,
            UUID.randomUUID().toString()
        )
        val adapter = moshi.adapter<AppIdRequest>(AppIdRequest::class.java)
        val body = RequestBody.create(JSON, adapter.toJson(appIdRequest))
        val request = Request.Builder()
            .url(VERDI_BASE_URL_TEST + REQUEST_CHECK_APP_ID)
            .addHeader("Accept", "application/json")
            .addHeader("Content-type", "application/json")
            .addHeader("Authorization", AUTH_CHECK_APP_ID)
            .post(body)
            .build()
        okClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                applicationHandler.post {
                    listener.onFailure(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                applicationHandler.post {
                    if (response.isSuccessful) {
                        if (response.body() == null) {
                            listener.onFailure(
                                ServerNotAvailableException(
                                    response.code(),
                                    response.message()
                                )
                            )
                        }

                        response.body()?.let {

                            val initialResponse =
                                moshi.adapter<AppIdResponse>(AppIdResponse::class.java)
                                    .fromJson(it.string())
                            when (initialResponse?.code) {
                                0 -> {
                                    Verdi.isAppIdAvailable = true
                                    listener.onSuccess(Any())
                                }
                                else -> {
                                    listener.onFailure(
                                        ErrorUtils.getException(
                                            initialResponse?.code,
                                            initialResponse?.message
                                        )
                                    )
                                }
                            }
                        }

                    } else {
                        listener.onFailure(
                            ServerNotAvailableException(response.code(), response.message())
                        )
                    }
                }
            }
        })
    }

    fun registerPerson(listener: ResponseListener<RegistrationResponse>) {
        if (!Verdi.isAppIdAvailable) {
            checkAppId(object : ResponseListener<Any> {
                override fun onFailure(e: Exception) {
                    listener.onFailure(e)
                }

                override fun onSuccess(response: Any) {
                    registerPerson(listener)
                }
            })
            return
        }
        val user = Verdi.verdiUser
        val publicKey = UUID.randomUUID().toString()
        val guid = UUID.randomUUID().toString()
        val deviceID: String = user.deviceId
        if (deviceID == "") {
            listener.onFailure(VerdiNotInitializedException())
            return
        }
        val signString = md5(
            guid +
                    user.serialNumber +
                    user.birthDate +
                    user.dateOfExpiry +
                    publicKey
        )
        val passRequest = PassportRequest(
            user.serialNumber,
            user.personalNumber,
            user.docType,
            user.birthDate,
            user.dateOfExpiry
        )
        val modelPersonRequest = ModelPersonRequest(passRequest)
        val answere = Answere(1, "OK")
        val base64Pass = user.imageFaceBase?.toBase64()
        Log.d("Base64ImageTag", base64Pass.toString())
        val personPhoto = ModelPersonPhotoRequest(answere, base64Pass, base64Pass)
        val model = Build.MODEL
        val modelMobileData =
            ModelMobileData(
                model,
                "",
                deviceID,
                ""
            )
        val serviceInfo = ServiceInfo()
        serviceInfo.scannerSerial = deviceID
        val modelServiceInfo = ModelServiceInfo()
        modelServiceInfo.serviceInfo = serviceInfo
        val passportRequest = PassportInfoRequest(
            appId = Verdi.config.appId,
            requestGuid = guid,
            signString = signString,
            clientPubKey = publicKey,
            modelMobileData = modelMobileData,
            modelPersonPassport = modelPersonRequest,
            modelPersonPhoto = personPhoto,
            modelServiceInfo = modelServiceInfo
        )
        Log.d(
            "RequestTag",
            moshi.adapter<PassportInfoRequest>(PassportInfoRequest::class.java)
                .toJson(passportRequest)
        )
        val adapter = moshi.adapter<PassportInfoRequest>(PassportInfoRequest::class.java)
        val body = RequestBody.create(JSON, adapter.toJson(passportRequest))
        val request = Request.Builder()
            .url(VERDI_BASE_URL_TEST + REQUEST_REGISTRATION)
            .addHeader("Accept", "application/json")
            .addHeader("Content-type", "application/json")
            .post(body)
            .build()

        okClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                applicationHandler.post {
                    listener.onFailure(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                applicationHandler.post {
                    if (response.isSuccessful) {
                        if (response.body() == null) {
                            listener.onFailure(
                                ServerNotAvailableException(
                                    response.code(),
                                    response.message()
                                )
                            )
                        }

                        response.body()?.let {
                            val initialResponse =
                                moshi.adapter<RegistrationResponse>(RegistrationResponse::class.java)
                                    .fromJson(it.string())
                            Log.d(
                                "TagCheck",
                                moshi.adapter<RegistrationResponse>(RegistrationResponse::class.java)
                                    .toJson(initialResponse)
                            )

                            when (initialResponse?.code) {
                                0 -> {
                                    listener.onSuccess(initialResponse)
                                }
                                else -> {
                                    listener.onFailure(
                                        ErrorUtils.getException(
                                            initialResponse?.code,
                                            initialResponse?.message
                                        )
                                    )
                                }
                            }
                        }

                    } else {
                        listener.onFailure(
                            ServerNotAvailableException(response.code(), response.message())
                        )
                    }
                }

            }
        })

    }


    fun verifyPerson(listener: ResponseListener<RegistrationResponse>) {
        if (!Verdi.isAppIdAvailable) {
            checkAppId(object : ResponseListener<Any> {
                override fun onFailure(e: Exception) {
                    listener.onFailure(e)
                }

                override fun onSuccess(response: Any) {
                    registerPerson(listener)
                }
            })
            return
        }
        val deviceSerialNumber: String = Verdi.verdiUser.serialNumber
        val guid = UUID.randomUUID().toString()
        val deviceID: String =
            Verdi.verdiUser.deviceId
        if (deviceID == "") {
            listener.onFailure(VerdiNotInitializedException())
            return
        }
        val signString = md5(buildString {
            guid + deviceSerialNumber + deviceID + "dataSource.getPubKey()"
        })
        val personPhoto = ModelPersonPhotoRequest(null, null, "")

        val model = Build.MODEL

        val modelMobileData =
            ModelMobileData(model, null, deviceID, null)

        val serviceInfo = ServiceInfo()
        serviceInfo.scannerSerial = deviceSerialNumber

        val modelServiceInfo = ModelServiceInfo()
        modelServiceInfo.serviceInfo = serviceInfo

        val passportRequest = PassportInfoRequest(
            appId = Verdi.config.appId,
            requestGuid = guid,
            signString = signString,
            clientPubKey = UUID.randomUUID().toString(),
            modelPersonPassport = null,
            modelPersonPhoto = personPhoto,
            modelServiceInfo = modelServiceInfo,
            modelMobileData = modelMobileData
        )

        Log.d(
            "RequestTag",
            moshi.adapter<PassportInfoRequest>(PassportInfoRequest::class.java)
                .toJson(passportRequest)
        )
        val adapter = moshi.adapter<PassportInfoRequest>(PassportInfoRequest::class.java)
        val body = RequestBody.create(JSON, adapter.toJson(passportRequest))
        val request = Request.Builder()
            .url(VERDI_BASE_URL_TEST + REQUEST_VERIFICATION)
            .addHeader("Accept", "application/json")
            .addHeader("Content-type", "application/json")
            .post(body)
            .build()

        okClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                applicationHandler.post {
                    listener.onFailure(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                applicationHandler.post {
                    if (response.isSuccessful) {
                        if (response.body() == null) {
                            listener.onFailure(
                                ServerNotAvailableException(
                                    response.code(),
                                    response.message()
                                )
                            )
                        }

                        response.body()?.let {
                            val initialResponse =
                                moshi.adapter<RegistrationResponse>(RegistrationResponse::class.java)
                                    .fromJson(it.string())
                            Log.d(
                                "TagCheck",
                                moshi.adapter<RegistrationResponse>(RegistrationResponse::class.java)
                                    .toJson(initialResponse)
                            )
                            applicationHandler.post {
                                when (initialResponse?.code) {
                                    0 -> {
                                        listener.onSuccess(initialResponse)
                                    }
                                    else -> {
                                        listener.onFailure(
                                            ErrorUtils.getException(
                                                initialResponse?.code,
                                                initialResponse?.message
                                            )
                                        )
                                    }
                                }
                            }
                        }

                    } else {
                        listener.onFailure(
                            ServerNotAvailableException(response.code(), response.message())
                        )
                    }
                }
            }
        })
    }
}