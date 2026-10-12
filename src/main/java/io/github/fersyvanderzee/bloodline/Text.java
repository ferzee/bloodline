package io.github.fersyvanderzee.bloodline;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Translatable text. Bloodline usually runs only on the server, so players may not have its
 * language files: every key carries its English text as a fallback, read from the mod's own
 * en_us.json. Players who have the mod (or a resource pack with its language files) see their
 * own language; everyone else sees English.
 */
public final class Text {
	private static final String PREFIX = Bloodline.MOD_ID + ".";
	private static final Map<String, String> ENGLISH = load();

	private Text() {
	}

	private static Map<String, String> load() {
		Map<String, String> english = new HashMap<>();
		try (InputStream in = Text.class.getResourceAsStream("/assets/bloodline/lang/en_us.json")) {
			if (in == null) {
				Bloodline.LOGGER.error("Bloodline's en_us.json is missing; text will show as translation keys");
				return english;
			}
			JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			json.entrySet().forEach(e -> english.put(e.getKey(), e.getValue().getAsString()));
		} catch (Exception e) {
			Bloodline.LOGGER.error("Couldn't read Bloodline's en_us.json", e);
		}
		return english;
	}

	/** Text for {@code bloodline.<key>}; arguments fill the %s placeholders and may be text, numbers or components. */
	public static MutableComponent t(String key, Object... args) {
		String full = PREFIX + key;
		return Component.translatableWithFallback(full, ENGLISH.getOrDefault(full, full), args);
	}

	/** Whether a key exists, for text chosen at runtime (such as per-trait or per-oath keys). */
	public static boolean has(String key) {
		return ENGLISH.containsKey(PREFIX + key);
	}
}
