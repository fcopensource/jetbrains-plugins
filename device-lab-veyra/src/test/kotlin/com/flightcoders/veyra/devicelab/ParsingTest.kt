// Unit tests: adb output (captured from a real Android 16 emulator), finding adb, and reading an Android project.
package com.flightcoders.veyra.devicelab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ParsingTest {
    @Test fun `adb devices - online, unauthorized and offline devices`() {
        val text = """
            * daemon not running; starting now at tcp:5037
            List of devices attached
            emulator-5554          device product:sdk_phone64_x86_64 model:Android_SDK_built_for_x86_64 device:emu64x transport_id:1
            R58N12ABCDE            unauthorized usb:1-1 transport_id:2
            192.168.1.20:5555      offline transport_id:3
            1A2B3C4D               device usb:2-1 product:husky model:Pixel_8_Pro device:husky transport_id:4
        """.trimIndent()
        val devices = parseDevices(text)
        assertEquals(4, devices.size)
        assertTrue(devices[0].online && devices[0].emulator)
        assertEquals("Android SDK built for x86_64 (emulator)", devices[0].label)
        assertFalse(devices[1].online)
        assertEquals("R58N12ABCDE: allow USB debugging on the phone", devices[1].label)
        assertEquals("192.168.1.20:5555: offline", devices[2].label)
        assertEquals("Pixel 8 Pro (device)", devices[3].label)
        assertFalse(devices[3].emulator)
        assertTrue(parseDevices("List of devices attached\n\n").isEmpty())
    }

    @Test fun `device state from one shell call`() {
        val text = listOf(
            "@@sdk", "36", "@@release", "16", "@@night", "Night mode: yes", "@@font", "1.3",
            "@@density", "Physical density: 420", "Override density: 546", "@@anim", "0.5", "@@layout", "true",
            "@@taps", "null", "@@rtl", "1", "@@airplane", "0",
        ).joinToString("\r\n")
        val state = stateFrom(parseSections(text))
        assertEquals(36, state.sdk)
        assertEquals("16", state.release)
        assertEquals(Theme.DARK, state.theme)
        assertEquals(1.3f, state.fontScale)
        assertEquals(420, state.physicalDensity)
        assertEquals(546, state.density)
        assertEquals(0.5f, state.animationScale)
        assertTrue(state.layoutBounds)
        assertFalse("null means never set: off", state.showTaps)
        assertTrue(state.forceRtl)
        assertFalse(state.airplane)
    }

    @Test fun `device state - defaults when values were never set`() {
        val state = stateFrom(parseSections("@@sdk\n29\n@@night\nNight mode: auto\n@@font\nnull\n@@density\nPhysical density: 440\n@@anim\nnull\n@@layout\n\n"))
        assertEquals(Theme.AUTO, state.theme)
        assertEquals(1f, state.fontScale)
        assertEquals(440, state.density)
        assertEquals(1f, state.animationScale)
        assertFalse(state.layoutBounds)
    }

    @Test fun `runtime permissions from dumpsys package`() {
        val text = """
            Packages:
              Package [com.android.contacts] (5f0c3a1):
                requested permissions:
                  android.permission.CAMERA
                install permissions:
                  android.permission.INTERNET: granted=true
                User 0: ceDataInode=0 installed=true
                  runtime permissions:
                    android.permission.POST_NOTIFICATIONS: granted=false, flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]
                    android.permission.READ_CALL_LOG: granted=true, flags=[ GRANTED_BY_DEFAULT ]
                    android.permission.READ_CONTACTS: granted=true, flags=[ GRANTED_BY_DEFAULT ]
                  enabledComponents:
                    com.android.contacts.Foo
            Queries:
        """.trimIndent()
        val permissions = parsePermissions(text)
        assertEquals(listOf("POST_NOTIFICATIONS", "READ_CALL_LOG", "READ_CONTACTS"), permissions.map { it.shortName })
        assertFalse(permissions[0].granted)
        assertTrue(permissions[2].granted)
        assertTrue(parsePermissions("Unable to find package: com.nope").isEmpty())
    }

    @Test fun `opening a link - which screen, how it started, how long`() {
        val cold = parseLaunch("Starting: Intent { }\nStatus: ok\nLaunchState: COLD\nActivity: com.android.dialer/.main.impl.MainActivity\nTotalTime: 2268\nWaitTime: 2277\nComplete\n")
        assertEquals("com.android.dialer/.main.impl.MainActivity", cold.activity)
        assertEquals("MainActivity (cold start, 2268 ms)", cold.summary)
        val open = parseLaunch("Warning: Activity not started, intent has been delivered to currently running top-most instance.\nStatus: ok\nLaunchState: UNKNOWN (0)\nActivity: com.shop/com.shop.ui.OrderActivity\nTotalTime: 0\n")
        assertEquals("OrderActivity (already open)", open.summary)
    }

    @Test fun `app language`() {
        assertEquals("ar", parseAppLocales("Locales for com.android.settings for user 0 are [ar]"))
        assertEquals("de", parseAppLocales("Locales for com.x for user 0 are [de,en-US]"))
        assertEquals("", parseAppLocales("Locales for com.x for user 0 are []"))
    }

    @Test fun `shell quoting keeps links with query strings and quotes intact`() {
        assertEquals("'myapp://cart?id=1&x=2'", shellQuote("myapp://cart?id=1&x=2"))
        assertEquals("'it'\\''s'", shellQuote("it's"))
    }

    @Test fun `font and display sizes`() {
        assertEquals(4, fontScales(33).size)
        assertEquals(2f, fontScales(34).last().second)
        assertEquals(1.3f, largestFontScale(30))
        assertEquals(546, densityFor(420, 1.3f))
        assertEquals(357, densityFor(420, 0.85f))
    }

    @Test fun `finding adb - settings, local properties, ANDROID_HOME, default SDK, PATH`() {
        val dir = Files.createTempDirectory("lab").toFile()
        File(dir, "local.properties").writeText("## Generated\nsdk.dir=C\\:\\\\Users\\\\me\\\\Android\\\\Sdk\n")
        val existing = setOf(
            "C:\\Users\\me\\Android\\Sdk\\platform-tools\\adb.exe",
            "D:\\sdk\\platform-tools\\adb.exe",
            "C:\\Users\\me\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe",
        ).map { File(it).path }.toSet()
        val exists: (File) -> Boolean = { it.path in existing }
        assertEquals(File("C:\\Users\\me\\Android\\Sdk\\platform-tools\\adb.exe").path, findAdb(null, dir.path, mapOf("ANDROID_HOME" to "D:\\sdk"), "C:\\Users\\me", true, false, exists))
        assertEquals(File("D:\\sdk\\platform-tools\\adb.exe").path, findAdb(null, null, mapOf("ANDROID_HOME" to "D:\\sdk"), "C:\\Users\\me", true, false, exists))
        assertEquals(File("C:\\Users\\me\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe").path,
            findAdb(null, null, mapOf("LOCALAPPDATA" to "C:\\Users\\me\\AppData\\Local"), "C:\\Users\\me", true, false, exists))
        assertEquals("/Users/me/Library/Android/sdk/platform-tools/adb".replace('/', File.separatorChar),
            findAdb(null, null, emptyMap(), "/Users/me", false, true) { it.path == "/Users/me/Library/Android/sdk/platform-tools/adb".replace('/', File.separatorChar) })
        assertNull(findAdb(null, null, emptyMap(), "/home/me", false, false) { false })
        assertEquals(File("E:\\tools\\adb.exe").path, findAdb("E:\\tools\\adb.exe", null, emptyMap(), "", true, false) { it.path == File("E:\\tools\\adb.exe").path })
        dir.deleteRecursively()
    }

    @Test fun `application ids from Kotlin and Groovy build files`() {
        assertEquals(listOf("com.shop.app"), applicationIdsIn("android {\n  defaultConfig {\n    applicationId = \"com.shop.app\"\n  }\n}"))
        assertEquals(listOf("com.shop.app"), applicationIdsIn("defaultConfig {\n    applicationId 'com.shop.app'\n}"))
        assertTrue("not a real id", applicationIdsIn("applicationId = libs.versions.id").isEmpty())
        assertTrue("comments don't count", applicationIdsIn("// applicationId = \"com.old\"").isEmpty())
    }

    @Test fun `deep links from a manifest`() {
        val manifest = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools">
              <application>
                <activity android:name=".Main" android:exported="true">
                  <intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter>
                  <intent-filter android:autoVerify="true">
                    <action android:name="android.intent.action.VIEW"/>
                    <category android:name="android.intent.category.DEFAULT"/><category android:name="android.intent.category.BROWSABLE"/>
                    <data android:scheme="https"/><data android:scheme="http"/>
                    <data android:host="shop.example.com"/>
                    <data android:pathPrefix="/item"/>
                  </intent-filter>
                  <intent-filter>
                    <action android:name="android.intent.action.VIEW"/>
                    <data android:scheme="shop" android:host="orders" android:pathPattern="/.*"/>
                  </intent-filter>
                  <intent-filter>
                    <action android:name="android.intent.action.VIEW"/>
                    <data android:scheme="tel"/>
                  </intent-filter>
                </activity>
              </application>
            </manifest>
        """.trimIndent()
        assertEquals(listOf("https://shop.example.com/item", "http://shop.example.com/item", "shop://orders/1", "tel:"), manifestLinks(manifest))
    }

    @Test fun `deep links from a Navigation graph`() {
        val nav = """
            <navigation xmlns:android="http://schemas.android.com/apk/res/android" xmlns:app="http://schemas.android.com/apk/res-auto" android:id="@+id/nav">
              <fragment android:id="@+id/product"><deepLink app:uri="shop://product/{id}"/></fragment>
              <fragment android:id="@+id/web"><deepLink app:uri="www.example.com/promo/{code}"/></fragment>
            </navigation>
        """.trimIndent()
        assertEquals(listOf("shop://product/1", "https://www.example.com/promo/1"), navigationLinks(nav))
    }

    @Test fun `a whole project - ids and links, build folders skipped`() {
        val root = Files.createTempDirectory("app").toFile()
        File(root, "app/src/main/res/navigation").mkdirs()
        File(root, "app/build/intermediates").mkdirs()
        File(root, "app/build.gradle.kts").writeText("android { defaultConfig { applicationId = \"com.flightcoders.demo\" } }")
        File(root, "app/build/intermediates/AndroidManifest.xml").writeText("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application><activity><intent-filter><action android:name=\"android.intent.action.VIEW\"/><data android:scheme=\"generated\"/></intent-filter></activity></application></manifest>")
        File(root, "app/src/main/AndroidManifest.xml").writeText("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application><activity><intent-filter><action android:name=\"android.intent.action.VIEW\"/><data android:scheme=\"demo\" android:host=\"home\"/></intent-filter></activity></application></manifest>")
        File(root, "app/src/main/res/navigation/nav_graph.xml").writeText("<navigation xmlns:app=\"http://schemas.android.com/apk/res-auto\"><fragment><deepLink app:uri=\"demo://cart\"/></fragment></navigation>")
        File(root, "app/src/main/res/navigation/broken.xml").writeText("<navigation")
        assertEquals(listOf("com.flightcoders.demo"), applicationIds(root))
        val links = deepLinks(root)
        assertEquals(listOf("demo://home", "demo://cart"), links.map { it.uri })
        assertEquals("app/src/main/AndroidManifest.xml", links[0].source)
        root.deleteRecursively()
    }
}
