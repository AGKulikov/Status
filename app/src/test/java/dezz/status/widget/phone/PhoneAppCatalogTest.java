/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import dezz.status.widget.R;

public class PhoneAppCatalogTest {
    @Test public void resolvesKnownBundleIdentifiersCaseInsensitively() {
        assertName("Сообщения", "com.apple.MobileSMS");
        assertName("Почта", "com.apple.mobilemail");
        assertName("Календарь", "com.apple.mobilecal");
        assertName("Телефон", "com.apple.mobilephone");
        assertName("FaceTime", "com.apple.facetime");
        assertName("WhatsApp", " net.whatsapp.WhatsApp ");
        assertName("Telegram", "ph.telegra.Telegraph");
        assertName("Signal", "org.whispersystems.signal");
        assertName("Viber", "com.viber");
        assertName("ВКонтакте", "com.vk.vkclient");
        assertName("Instagram", "com.burbn.instagram");
        assertName("Facebook", "com.facebook.Facebook");
        assertName("Messenger", "com.facebook.Messenger");
        assertName("Gmail", "com.google.Gmail");
        assertName("Outlook", "com.microsoft.Office.Outlook");
        assertName("Яндекс Почта", "ru.yandex.mobile.mail");
        assertName("Яндекс Музыка", "ru.yandex.mobile.music");
        assertName("Яндекс Карты", "ru.yandex.mobile.maps");
        assertName("Google Карты", "com.google.Maps");
        assertName("YouTube", "com.google.ios.youtube");
        assertName("TikTok", "com.zhiliaoapp.musically");
        assertName("Slack", "com.tinyspeck.chatlyio");
        assertName("Microsoft Teams", "com.microsoft.skype.teams");
        assertName("Discord", "com.hammerandchisel.discord");
    }

    @Test public void everyRequestedAppFamilyHasASemanticIcon() {
        assertEquals("messages", PhoneAppCatalog.iconKey("com.apple.MobileSMS", 0));
        assertEquals("mail", PhoneAppCatalog.iconKey("com.google.Gmail", 0));
        assertEquals("calendar", PhoneAppCatalog.iconKey("com.apple.mobilecal", 0));
        assertEquals("phone", PhoneAppCatalog.iconKey("com.apple.facetime", 0));
        assertEquals("chat", PhoneAppCatalog.iconKey("org.whispersystems.signal", 0));
        assertEquals("social", PhoneAppCatalog.iconKey("com.vk.vkclient", 0));
        assertEquals("photo", PhoneAppCatalog.iconKey("com.burbn.instagram", 0));
        assertEquals("music", PhoneAppCatalog.iconKey("ru.yandex.mobile.music", 0));
        assertEquals("maps", PhoneAppCatalog.iconKey("com.google.Maps", 0));
        assertEquals("video", PhoneAppCatalog.iconKey("com.google.ios.youtube", 0));
        assertEquals("work", PhoneAppCatalog.iconKey("com.tinyspeck.chatlyio", 0));
        assertEquals("chat", PhoneAppCatalog.iconKey("com.hammerandchisel.discord", 0));
    }

    @Test public void appIdentityWinsOverAncsCategory() {
        assertEquals("chat",
                PhoneAppCatalog.iconKey("net.whatsapp.WhatsApp", 6));
        assertEquals(R.drawable.ic_phone_app_chat,
                PhoneAppCatalog.iconResource("net.whatsapp.WhatsApp", 6));
    }

    @Test public void unknownAppsUseEveryAncsCategoryFallback() {
        assertEquals("notification", PhoneAppCatalog.iconKey("com.example.other", 0));
        assertEquals("phone", PhoneAppCatalog.iconKey("com.example.call", 1));
        assertEquals("missed_call", PhoneAppCatalog.iconKey("com.example.missed", 2));
        assertEquals("voicemail", PhoneAppCatalog.iconKey("com.example.voicemail", 3));
        assertEquals("social", PhoneAppCatalog.iconKey("com.example.social", 4));
        assertEquals("calendar", PhoneAppCatalog.iconKey("com.example.schedule", 5));
        assertEquals("mail", PhoneAppCatalog.iconKey("com.example.email", 6));
        assertEquals("news", PhoneAppCatalog.iconKey("com.example.news", 7));
        assertEquals("health", PhoneAppCatalog.iconKey("com.example.health", 8));
        assertEquals("finance", PhoneAppCatalog.iconKey("com.example.finance", 9));
        assertEquals("maps", PhoneAppCatalog.iconKey("com.example.location", 10));
        assertEquals("video", PhoneAppCatalog.iconKey("com.example.entertainment", 11));
    }

    @Test public void unknownNameFallbackIsReadableAndIconsAreRealResources() {
        assertEquals("Client",
                PhoneAppCatalog.displayNameFallback("com.example.client"));
        assertEquals("Приложение iPhone",
                PhoneAppCatalog.displayNameFallback(" "));
        assertNotEquals(0, PhoneAppCatalog.iconResource(null, 0));
        assertEquals(R.drawable.ic_phone_app_mail,
                PhoneAppCatalog.iconResource("unknown.mail.client", 6));
    }

    @Test public void everyPhoneIconIsARepositoryVector() throws Exception {
        for (String key : new String[] {
                "calendar", "chat", "finance", "health", "mail", "maps", "messages",
                "missed_call", "music", "news", "notification", "phone", "photo",
                "social", "video", "voicemail", "work"
        }) {
            Path fromRoot = Paths.get("app", "src", "main", "res", "drawable",
                    "ic_phone_app_" + key + ".xml");
            Path fromApp = Paths.get("src", "main", "res", "drawable",
                    "ic_phone_app_" + key + ".xml");
            Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
            assertTrue("Missing vector " + key, Files.isRegularFile(file));
            String xml = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            assertTrue("Not a vector " + key, xml.contains("<vector"));
            assertTrue("Vector has no paths " + key, xml.contains("<path"));
        }
    }

    private static void assertName(String expected, String bundleIdentifier) {
        assertEquals(expected, PhoneAppCatalog.displayNameFallback(bundleIdentifier));
    }
}
