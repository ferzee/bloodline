package io.github.fersyvanderzee.bloodline.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

import java.util.Optional;

/** A quest the current head of the family has finished, kept so it can be shown crossed out. */
public record CompletedQuest(Quest.Type type, String title, Component task, int generation, Optional<String> line, int act) {
	public static final Codec<CompletedQuest> CODEC = RecordCodecBuilder.create(i -> i.group(
		Quest.Type.CODEC.fieldOf("type").forGetter(CompletedQuest::type),
		Codec.STRING.fieldOf("title").forGetter(CompletedQuest::title),
		ComponentSerialization.CODEC.fieldOf("task").forGetter(CompletedQuest::task),
		Codec.INT.fieldOf("generation").forGetter(CompletedQuest::generation),
		Codec.STRING.optionalFieldOf("line").forGetter(CompletedQuest::line),
		Codec.INT.optionalFieldOf("act", 0).forGetter(CompletedQuest::act)
	).apply(i, CompletedQuest::new));

	public static CompletedQuest of(Quest quest, int generation) {
		return new CompletedQuest(quest.type, quest.title, quest.task, generation, quest.line, quest.act);
	}
}
