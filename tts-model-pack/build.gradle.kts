plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("tts_model_pack")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}
