package com.example.touchgrass.features.focus.insights

/** Classifies installed applications that are commonly distracting. */
object DistractionClassifier {
    /** Package names of well-known time sinks, regardless of their declared category. */
    val KNOWN_DISTRACTING: Set<String> = setOf(
        "com.google.android.youtube",
        "com.instagram.android",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.facebook.katana",
        "com.facebook.lite",
        "com.snapchat.android",
        "com.twitter.android",
        "com.reddit.frontpage",
        "com.netflix.mediaclient",
        "com.amazon.avod.thirdpartyclient",
        "in.startv.hotstar",
        "com.pinterest"
    )

    /** True for social, short-video, video, game, and news applications. */
    fun isDistracting(packageName: String, category: Int?): Boolean =
        packageName in KNOWN_DISTRACTING || category in DISTRACTING_CATEGORIES

    private const val CATEGORY_GAME = 0
    private const val CATEGORY_VIDEO = 2
    private const val CATEGORY_SOCIAL = 4
    private const val CATEGORY_NEWS = 5

    private val DISTRACTING_CATEGORIES = setOf(
        CATEGORY_GAME,
        CATEGORY_VIDEO,
        CATEGORY_SOCIAL,
        CATEGORY_NEWS
    )
}
