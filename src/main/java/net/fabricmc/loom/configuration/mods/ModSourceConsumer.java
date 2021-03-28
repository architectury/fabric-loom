package net.fabricmc.loom.configuration.mods;

import net.fabricmc.loom.util.function.NamedSupplier;

import org.gradle.api.Named;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.SourceSet;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class ModSourceConsumer implements Named {
	private final Project project;
	private final String name;
	private final Consumer<Supplier<SourceSet>> consumer;

	public ModSourceConsumer(Project project, String name, Consumer<Supplier<SourceSet>> consumer) {
		this.project = project;
		this.name = name;
		this.consumer = consumer;
	}

	private Supplier<SourceSet> named(Supplier<SourceSet> supplier) {
		return new NamedSupplier<SourceSet>() {
			@Override
			public SourceSet get() {
				return supplier.get();
			}

			@Override
			public String getName() {
				return name;
			}
		};
	}

	public void add(Object... sourceSets) {
		for (Object sourceSet : sourceSets) {
			if (sourceSet instanceof SourceSet)
				consumer.accept(named(() -> (SourceSet) sourceSet));
			else
				consumer.accept(named(() -> project.getConvention().getPlugin(JavaPluginConvention.class).getSourceSets().findByName(String.valueOf(sourceSet))));
		}
	}

	@Override
	public String getName() {
		return name;
	}
}
