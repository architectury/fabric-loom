/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016, 2017, 2018 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.configuration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.google.common.collect.ImmutableMap;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.UnknownTaskException;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.javadoc.Javadoc;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.build.JarRemapper;
import net.fabricmc.loom.build.NestedJars;
import net.fabricmc.loom.build.mixin.JavaApInvoker;
import net.fabricmc.loom.build.mixin.KaptApInvoker;
import net.fabricmc.loom.build.mixin.ScalaApInvoker;
import net.fabricmc.loom.configuration.ide.SetupIntelijRunConfigs;
import net.fabricmc.loom.configuration.providers.LaunchProvider;
import net.fabricmc.loom.configuration.providers.MinecraftProvider;
import net.fabricmc.loom.configuration.providers.forge.ForgeProvider;
import net.fabricmc.loom.configuration.providers.forge.ForgeUniversalProvider;
import net.fabricmc.loom.configuration.providers.forge.ForgeUserdevProvider;
import net.fabricmc.loom.configuration.providers.forge.McpConfigProvider;
import net.fabricmc.loom.configuration.providers.forge.PatchProvider;
import net.fabricmc.loom.configuration.providers.forge.SrgProvider;
import net.fabricmc.loom.configuration.providers.mappings.MappingsProvider;
import net.fabricmc.loom.task.AbstractLoomTask;
import net.fabricmc.loom.task.GenVsCodeProjectTask;
import net.fabricmc.loom.task.RemapAllSourcesTask;
import net.fabricmc.loom.task.RemapJarTask;
import net.fabricmc.loom.task.RemapSourcesJarTask;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.SourceRemapper;

/**
 * Add Minecraft dependencies to compile time.
 */
public final class CompileConfiguration {
	private CompileConfiguration() {
	}

	public static void setupConfigurations(Project project) {
		Configuration modCompileClasspathConfig = project.getConfigurations().maybeCreate(Constants.Configurations.MOD_COMPILE_CLASSPATH);
		modCompileClasspathConfig.setTransitive(true);
		Configuration modCompileClasspathMappedConfig = project.getConfigurations().maybeCreate(Constants.Configurations.MOD_COMPILE_CLASSPATH_MAPPED);
		modCompileClasspathMappedConfig.setTransitive(false);

		Configuration minecraftNamedConfig = project.getConfigurations().maybeCreate(Constants.Configurations.MINECRAFT_NAMED);
		minecraftNamedConfig.setTransitive(false); // The launchers do not recurse dependencies
		Configuration minecraftDependenciesConfig = project.getConfigurations().maybeCreate(Constants.Configurations.MINECRAFT_DEPENDENCIES);
		minecraftDependenciesConfig.setTransitive(false);
		Configuration loaderDependenciesConfig = project.getConfigurations().maybeCreate(Constants.Configurations.LOADER_DEPENDENCIES);
		loaderDependenciesConfig.setTransitive(false);
		Configuration minecraftConfig = project.getConfigurations().maybeCreate(Constants.Configurations.MINECRAFT);
		minecraftConfig.setTransitive(false);

		project.afterEvaluate(project1 -> {
			if (project.getExtensions().getByType(LoomGradleExtension.class).shouldGenerateSrgTiny()) {
				Configuration srg = project.getConfigurations().maybeCreate(Constants.Configurations.SRG);
				srg.setTransitive(false);
			}

			if (project.getExtensions().getByType(LoomGradleExtension.class).isDataGenEnabled()) {
				project.getConvention().getPlugin(JavaPluginConvention.class).getSourceSets().getByName("main").resources(files -> {
					files.srcDir(project.file("src/generated/resources"));
				});
			}
		});

		if (project.getExtensions().getByType(LoomGradleExtension.class).isForge()) {
			Configuration forgeConfig = project.getConfigurations().maybeCreate(Constants.Configurations.FORGE);
			forgeConfig.setTransitive(false);
			Configuration forgeUserdevConfig = project.getConfigurations().maybeCreate(Constants.Configurations.FORGE_USERDEV);
			forgeUserdevConfig.setTransitive(false);
			Configuration forgeInstallerConfig = project.getConfigurations().maybeCreate(Constants.Configurations.FORGE_INSTALLER);
			forgeInstallerConfig.setTransitive(false);
			Configuration forgeUniversalConfig = project.getConfigurations().maybeCreate(Constants.Configurations.FORGE_UNIVERSAL);
			forgeUniversalConfig.setTransitive(false);
			Configuration forgeDependencies = project.getConfigurations().maybeCreate(Constants.Configurations.FORGE_DEPENDENCIES);
			forgeDependencies.setTransitive(false);
			Configuration mcpConfig = project.getConfigurations().maybeCreate(Constants.Configurations.MCP_CONFIG);
			mcpConfig.setTransitive(false);

			extendsFrom(Constants.Configurations.MINECRAFT_DEPENDENCIES, Constants.Configurations.FORGE_DEPENDENCIES, project);
		}

		if (project.getExtensions().getByType(LoomGradleExtension.class).supportsInclude()) {
			Configuration includeConfig = project.getConfigurations().maybeCreate(Constants.Configurations.INCLUDE);
			includeConfig.setTransitive(false); // Dont get transitive deps
		}

		project.getConfigurations().maybeCreate(Constants.Configurations.MAPPINGS);
		project.getConfigurations().maybeCreate(Constants.Configurations.MAPPINGS_FINAL);
		project.getConfigurations().maybeCreate(Constants.Configurations.LOOM_DEVELOPMENT_DEPENDENCIES);

		for (RemappedConfigurationEntry entry : Constants.MOD_COMPILE_ENTRIES) {
			Configuration compileModsConfig = project.getConfigurations().maybeCreate(entry.getSourceConfiguration());
			compileModsConfig.setTransitive(true);
			Configuration compileModsMappedConfig = project.getConfigurations().maybeCreate(entry.getRemappedConfiguration());
			compileModsMappedConfig.setTransitive(false); // Don't get transitive deps of already remapped mods

			extendsFrom(entry.getTargetConfiguration(project.getConfigurations()), entry.getRemappedConfiguration(), project);

			if (entry.isOnModCompileClasspath()) {
				extendsFrom(Constants.Configurations.MOD_COMPILE_CLASSPATH, entry.getSourceConfiguration(), project);
				extendsFrom(Constants.Configurations.MOD_COMPILE_CLASSPATH_MAPPED, entry.getRemappedConfiguration(), project);
			}
		}

		extendsFrom(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME, Constants.Configurations.MINECRAFT_NAMED, project);
		extendsFrom(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME, Constants.Configurations.MINECRAFT_NAMED, project);
		extendsFrom(JavaPlugin.TEST_COMPILE_CLASSPATH_CONFIGURATION_NAME, Constants.Configurations.MINECRAFT_NAMED, project);
		extendsFrom(JavaPlugin.TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME, Constants.Configurations.MINECRAFT_NAMED, project);

		extendsFrom(Constants.Configurations.LOADER_DEPENDENCIES, Constants.Configurations.MINECRAFT_DEPENDENCIES, project);
		extendsFrom(Constants.Configurations.MINECRAFT_NAMED, Constants.Configurations.LOADER_DEPENDENCIES, project);

		extendsFrom(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME, Constants.Configurations.MAPPINGS_FINAL, project);

		extendsFrom(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME, Constants.Configurations.LOOM_DEVELOPMENT_DEPENDENCIES, project);
	}

	/**
	 * Permit to add a Maven repository to a target project.
	 *
	 * @param target The target project
	 * @param name   The name of the repository
	 * @param url    The URL of the repository
	 * @return An object containing the name and the URL of the repository that can be modified later
	 */
	public static MavenArtifactRepository addMavenRepo(Project target, final String name, final String url) {
		return addMavenRepo(target, name, url, repo -> {
		});
	}

	public static MavenArtifactRepository addMavenRepo(Project target, final String name, final String url, final Action<MavenArtifactRepository> action) {
		return target.getRepositories().maven(repo -> {
			repo.setName(name);
			repo.setUrl(url);
			action.execute(repo);
		});
	}

	public static void configureCompile(Project project) {
		JavaPluginConvention javaModule = (JavaPluginConvention) project.getConvention().getPlugins().get("java");

		SourceSet main = javaModule.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);

		Javadoc javadoc = (Javadoc) project.getTasks().getByName(JavaPlugin.JAVADOC_TASK_NAME);
		javadoc.setClasspath(main.getOutput().plus(main.getCompileClasspath()));

		project.afterEvaluate(project1 -> {
			LoomGradleExtension extension = project1.getExtensions().getByType(LoomGradleExtension.class);

			project1.getRepositories().flatDir(flatDirectoryArtifactRepository -> {
				flatDirectoryArtifactRepository.dir(extension.getRootProjectBuildCache());
				flatDirectoryArtifactRepository.setName("UserLocalCacheFiles");
			});

			project1.getRepositories().maven(mavenArtifactRepository -> {
				mavenArtifactRepository.setUrl(extension.getRemappedModCache());
				mavenArtifactRepository.setName("UserLocalRemappedMods");
			});

			project1.getRepositories().maven(mavenArtifactRepository -> {
				mavenArtifactRepository.setName("Fabric");
				mavenArtifactRepository.setUrl("https://maven.fabricmc.net/");
			});

			project1.getRepositories().maven(mavenArtifactRepository -> {
				mavenArtifactRepository.setName("Mojang");
				mavenArtifactRepository.setUrl("https://libraries.minecraft.net/");
			});

			project1.getRepositories().maven(mavenArtifactRepository -> {
				mavenArtifactRepository.setName("Forge");
				mavenArtifactRepository.setUrl("https://maven.minecraftforge.net/");

				mavenArtifactRepository.metadataSources(sources -> {
					sources.mavenPom();
					sources.artifact();

					try {
						MavenArtifactRepository.MetadataSources.class.getDeclaredMethod("ignoreGradleMetadataRedirection")
								.invoke(sources);
					} catch (Throwable ignored) {
						// Method not available
					}
				});
			});

			project1.getRepositories().mavenCentral();

			LoomDependencyManager dependencyManager = new LoomDependencyManager();
			extension.setDependencyManager(dependencyManager);

			dependencyManager.addProvider(new MinecraftProvider(project));

			if (extension.isForge()) {
				dependencyManager.addProvider(new ForgeProvider(project));
				dependencyManager.addProvider(new ForgeUserdevProvider(project));
			}

			if (extension.shouldGenerateSrgTiny()) {
				dependencyManager.addProvider(new SrgProvider(project));
			}

			if (extension.isForge()) {
				dependencyManager.addProvider(new McpConfigProvider(project));
				dependencyManager.addProvider(new PatchProvider(project));
				dependencyManager.addProvider(new ForgeUniversalProvider(project));
			}

			dependencyManager.addProvider(new MappingsProvider(project));
			dependencyManager.addProvider(new LaunchProvider(project));

			dependencyManager.handleDependencies(project1);

			project1.getTasks().getByName("idea").finalizedBy(project1.getTasks().getByName("genIdeaWorkspace"));
			project1.getTasks().getByName("eclipse").finalizedBy(project1.getTasks().getByName("genEclipseRuns"));
			project1.getTasks().getByName("cleanEclipse").finalizedBy(project1.getTasks().getByName("cleanEclipseRuns"));

			SetupIntelijRunConfigs.setup(project1);
			GenVsCodeProjectTask.generate(project1);

			// Enables the default mod remapper
			if (extension.remapMod) {
				AbstractArchiveTask jarTask = (AbstractArchiveTask) project1.getTasks().getByName("jar");
				RemapJarTask remapJarTask = (RemapJarTask) project1.getTasks().findByName("remapJar");

				assert remapJarTask != null;

				if (!remapJarTask.getInput().isPresent()) {
					jarTask.setClassifier("dev");
					remapJarTask.setClassifier("");
					remapJarTask.getInput().set(jarTask.getArchivePath());
				}

				if (extension.isForge()) {
					((Jar) jarTask).manifest(manifest -> {
						List<String> configs = new ArrayList<>();

						if (extension.mixinConfig != null) {
							configs.add(extension.mixinConfig);
						}

						if (extension.mixinConfigs != null) {
							configs.addAll(extension.mixinConfigs);
						}

						manifest.attributes(ImmutableMap.of("MixinConfigs", String.join(",", configs)));
					});
				}

				extension.getUnmappedModCollection().from(jarTask);
				remapJarTask.getAddNestedDependencies().set(true);
				remapJarTask.getRemapAccessWidener().set(true);

				project1.getArtifacts().add("archives", remapJarTask);
				remapJarTask.dependsOn(jarTask);
				project1.getTasks().getByName("build").dependsOn(remapJarTask);

				project.getTasks().withType(RemapJarTask.class).forEach(task -> {
					if (extension.supportsInclude() && task.getAddNestedDependencies().getOrElse(false)) {
						NestedJars.getRequiredTasks(project1).forEach(task::dependsOn);
					}
				});

				SourceRemapper remapper = null;
				Task parentTask = project1.getTasks().getByName("build");

				if (extension.isShareCaches()) {
					Project rootProject = project.getRootProject();

					if (extension.isRootProject()) {
						SourceRemapper sourceRemapper = new SourceRemapper(rootProject, false);
						JarRemapper jarRemapper = new JarRemapper();

						remapJarTask.jarRemapper = jarRemapper;

						rootProject.getTasks().register("remapAllSources", RemapAllSourcesTask.class, task -> {
							task.sourceRemapper = sourceRemapper;
							task.doLast(t -> sourceRemapper.remapAll());
						});

						parentTask = rootProject.getTasks().getByName("remapAllSources");

						rootProject.getTasks().register("remapAllJars", AbstractLoomTask.class, task -> {
							task.doLast(t -> {
								try {
									jarRemapper.remap(rootProject);
								} catch (IOException e) {
									throw new RuntimeException("Failed to remap jars", e);
								}
							});
						});
					} else {
						parentTask = rootProject.getTasks().getByName("remapAllSources");
						remapper = ((RemapAllSourcesTask) parentTask).sourceRemapper;

						remapJarTask.jarRemapper = ((RemapJarTask) rootProject.getTasks().getByName("remapJar")).jarRemapper;

						project1.getTasks().getByName("build").dependsOn(parentTask);
						project1.getTasks().getByName("build").dependsOn(rootProject.getTasks().getByName("remapAllJars"));
						rootProject.getTasks().getByName("remapAllJars").dependsOn(project1.getTasks().getByName("remapJar"));
					}
				}

				try {
					AbstractArchiveTask sourcesTask = (AbstractArchiveTask) project1.getTasks().getByName("sourcesJar");

					RemapSourcesJarTask remapSourcesJarTask = (RemapSourcesJarTask) project1.getTasks().findByName("remapSourcesJar");
					remapSourcesJarTask.setOutput(sourcesTask.getArchivePath());
					sourcesTask.setClassifier(sourcesTask.getClassifier() == null ? "dev" : sourcesTask.getClassifier() + "-dev");
					remapSourcesJarTask.setInput(sourcesTask.getArchivePath());
					remapSourcesJarTask.doLast(task -> project1.getArtifacts().add("archives", remapSourcesJarTask.getOutput()));
					remapSourcesJarTask.dependsOn(sourcesTask);

					if (extension.isShareCaches()) {
						remapSourcesJarTask.setSourceRemapper(remapper);
					}

					parentTask.dependsOn(remapSourcesJarTask);
				} catch (UnknownTaskException ignored) {
					// pass
				}
			} else {
				AbstractArchiveTask jarTask = (AbstractArchiveTask) project1.getTasks().getByName("jar");
				extension.getUnmappedModCollection().from(jarTask);
			}

			// Disable some things used by log4j via the mixin AP that prevent it from being garbage collected
			System.setProperty("log4j2.disable.jmx", "true");
			System.setProperty("log4j.shutdownHookEnabled", "false");
			System.setProperty("log4j.skipJansi", "true");

			project.getLogger().info("Configuring compiler arguments for Java");
			new JavaApInvoker(project).configureMixin();

			if (project.getPluginManager().hasPlugin("scala")) {
				project.getLogger().info("Configuring compiler arguments for Scala");
				new ScalaApInvoker(project).configureMixin();
			}

			if (project.getPluginManager().hasPlugin("org.jetbrains.kotlin.kapt")) {
				project.getLogger().info("Configuring compiler arguments for Kapt plugin");
				new KaptApInvoker(project).configureMixin();
			}
		});

		if (project.getPluginManager().hasPlugin("org.jetbrains.kotlin.kapt")) {
			// If loom is applied after kapt, then kapt will use the AP arguments too early for loom to pass the arguments we need for mixin.
			throw new IllegalArgumentException("fabric-loom must be applied BEFORE kapt in the plugins { } block.");
		}
	}

	private static void extendsFrom(String a, String b, Project project) {
		project.getConfigurations().getByName(a).extendsFrom(project.getConfigurations().getByName(b));
	}
}
