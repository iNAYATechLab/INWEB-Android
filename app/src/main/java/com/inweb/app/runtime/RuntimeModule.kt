package com.inweb.app.runtime

/*
 * ════════════════════════════════════════════════════════════════
 *  INWEB — Runtime Module registry
 * ════════════════════════════════════════════════════════════════
 *
 *  📦 কেন এই ফাইল?
 *  মূল APK যেন ছোট থাকে, তাই ভারী সার্ভার বাইনারিগুলো (node, caddy,
 *  cloudflared) অ্যাপের ভেতর বান্ডল না করে **আলাদা runtime APK** হিসেবে
 *  GitHub Release থেকে ডাউনলোড করা হবে।
 *
 *  🔐 কেন আলাদা APK-তে ইনস্টল করে দিই, সরাসরি files/ নামাই না কেন?
 *  Android 10+ (targetSdk ≥ 29) W^X SELinux restriction: অ্যাপের নিজস্ব data
 *  ডিরেক্টরির (`/data/user/0/<pkg>/files/...`) ফাইলে `execve()` ব্লকড —
 *  আমাদের beta.6 ডায়াগনস্টিকে হুবহু `error=13, Permission denied` দেখা গেছে।
 *  কিন্তু ইনস্টল করা APK-র native lib dir (`/data/app/.../lib/arm64`)
 *  SELinux-এ `app_lib_file` → exec **allowed**। তাই module = APK-ই থাকতে হবে।
 *
 *  shared libraries (libssl, libicu, …) মূল অ্যাপের native lib dir থেকেই
 *  resolve হয়ে যায় (ServerManager LD_LIBRARY_PATH-তে দুটো ডিরই দেয়), তাই
 *  module APK-তে শুধু তার **unique** ফাইলগুলো যায় — মাপা ডেটা:
 *     node  → 15.4 MB compressed · caddy → 17.2 MB · cloudflared → 9.1 MB
 */
enum class RuntimeModule(
    /** ছোট id — prefs/asset নামে ব্যবহৃত */
    val id: String,
    /** module APK-র applicationId */
    val packageName: String,
    /** GitHub Release asset নাম (pattern, {ver} প্লেসহোল্ডার) */
    val assetPattern: String,
    /** এই module যেসব executable যোগায় (jniLibs naming) */
    val executables: List<String>,
    /** UI-তে দেখানো নাম */
    val displayName: String,
    /** কত MB ডাউনলোড হবে (অনুমান, UI হিন্টের জন্য) */
    val approxMb: Int
) {
    NODE(
        id = "node",
        packageName = "com.inweb.app.runtime.node",
        assetPattern = "INWEB-runtime-node-{ver}.apk",
        executables = listOf("libexec_node.so"),
        displayName = "Node.js runtime",
        approxMb = 16
    ),
    CADDY(
        id = "caddy",
        packageName = "com.inweb.app.runtime.caddy",
        assetPattern = "INWEB-runtime-caddy-{ver}.apk",
        executables = listOf("libexec_caddy.so"),
        displayName = "Caddy server",
        approxMb = 18
    ),
    TUNNEL(
        id = "tunnel",
        packageName = "com.inweb.app.runtime.tunnel",
        assetPattern = "INWEB-runtime-tunnel-{ver}.apk",
        executables = listOf("libexec_cloudflared.so"),
        displayName = "Cloudflare Tunnel",
        approxMb = 10
    );

    companion object {
        fun byId(id: String?): RuntimeModule? = entries.firstOrNull { it.id == id }

        /** কোনো executable এই module-এর দেওয়া কিনা (libexec_nginx.so / nginx দুটোই ম্যাপ করে) */
        fun owning(fileName: String): RuntimeModule? =
            entries.firstOrNull { m ->
                m.executables.any { it == fileName || it == "libexec_${fileName.removeSuffix(".so")}.so" }
            }
    }
}
