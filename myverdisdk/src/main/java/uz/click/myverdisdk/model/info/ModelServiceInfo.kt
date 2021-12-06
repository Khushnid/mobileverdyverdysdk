package uz.click.myverdisdk.model.info

import android.os.Parcelable
import com.squareup.moshi.Json
import kotlinx.android.parcel.Parcelize
import uz.click.myverdisdk.model.request.Answer

@Parcelize
data class ModelServiceInfo(
    @field:Json(name = "Answere")
    var answer: Answer? = null,
    @field:Json(name = "ServiceInfo")
    var serviceInfo: ServiceInfo? = null
) : Parcelable