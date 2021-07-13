/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016-2021 FabricMC
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

package net.fabricmc.loom.configuration.providers.minecraft;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableMap;
import dev.architectury.tinyremapper.IMappingProvider;
import dev.architectury.tinyremapper.NonClassCopyMode;
import dev.architectury.tinyremapper.OutputConsumerPath;
import dev.architectury.tinyremapper.TinyRemapper;
import org.gradle.api.Project;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.loom.configuration.DependencyProvider;
import net.fabricmc.loom.configuration.providers.MinecraftProviderImpl;
import net.fabricmc.loom.configuration.providers.mappings.MappingsProviderImpl;
import net.fabricmc.loom.configuration.providers.minecraft.tr.OutputRemappingHandler;
import net.fabricmc.loom.configuration.sources.ForgeSourcesRemapper;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DownloadUtil;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.OperatingSystem;
import net.fabricmc.loom.util.ThreadingUtils;
import net.fabricmc.loom.util.TinyRemapperMappingsHelper;
import net.fabricmc.loom.util.srg.AtRemapper;
import net.fabricmc.loom.util.srg.CoreModClassRemapper;
import net.fabricmc.loom.util.srg.InnerClassRemapper;
import net.fabricmc.mapping.tree.TinyTree;

public class MinecraftMappedProvider extends DependencyProvider {
	public static final Map<String, String> JSR_TO_JETBRAINS = new ImmutableMap.Builder<String, String>()
			.put("javax/annotation/Nullable", "org/jetbrains/annotations/Nullable")
			.put("javax/annotation/Nonnull", "org/jetbrains/annotations/NotNull")
			.put("javax/annotation/concurrent/Immutable", "org/jetbrains/annotations/Unmodifiable")
			.build();

	private File inputJar;
	private File inputForgeJar;
	private File minecraftMappedJar;
	private File minecraftIntermediaryJar;
	private File minecraftSrgJar;
	private File forgeMappedJar;
	private File forgeIntermediaryJar;
	private File forgeSrgJar;

	private MinecraftProviderImpl minecraftProvider;

	public MinecraftMappedProvider(Project project) {
		super(project);
	}

	@Override
	public void provide(DependencyInfo dependency, Consumer<Runnable> postPopulationScheduler) throws Exception {
		if (!getExtension().getMappingsProvider().tinyMappings.exists()) {
			throw new RuntimeException("mappings file not found");
		}

		if (!inputJar.exists()) {
			throw new RuntimeException("input merged jar not found");
		}

		boolean isForgeAtDirty = getExtension().isForge() && getExtension().getMappingsProvider().patchedProvider.isAtDirty();
		TinyRemapper[] remapperArray = new TinyRemapper[] {null};

		if (!minecraftMappedJar.exists() || !getIntermediaryJar().exists() || (getExtension().isForge() && !getSrgJar().exists()) || isRefreshDeps() || isForgeAtDirty) {
			if (minecraftMappedJar.exists()) {
				minecraftMappedJar.delete();
			}

			minecraftMappedJar.getParentFile().mkdirs();

			if (minecraftIntermediaryJar.exists()) {
				minecraftIntermediaryJar.delete();
			}

			if (getExtension().isForge() && minecraftSrgJar.exists()) {
				minecraftSrgJar.delete();
			}

			try {
				mapMinecraftJar(remapperArray);
			} catch (Throwable t) {
				// Cleanup some some things that may be in a bad state now
				DownloadUtil.delete(minecraftMappedJar);
				DownloadUtil.delete(minecraftIntermediaryJar);
				getExtension().getMinecraftProvider().deleteFiles();

				if (getExtension().isForge()) {
					DownloadUtil.delete(minecraftSrgJar);
				}

				getExtension().getMappingsProvider().cleanFiles();
				throw new RuntimeException("Failed to remap minecraft", t);
			}
		}

		if (getExtension().isForge() && (!getForgeMappedJar().exists() || !getForgeIntermediaryJar().exists() || !getForgeSrgJar().exists() || isRefreshDeps() || isForgeAtDirty)) {
			if (getForgeMappedJar().exists()) {
				getForgeMappedJar().delete();
			}

			getForgeMappedJar().getParentFile().mkdirs();
			getForgeIntermediaryJar().delete();
			getForgeSrgJar().delete();

			try {
				mapForgeJar(remapperArray);
			} catch (Throwable t) {
				DownloadUtil.delete(forgeMappedJar);
				DownloadUtil.delete(forgeSrgJar);
				DownloadUtil.delete(forgeIntermediaryJar);
				throw new RuntimeException("Failed to remap forge", t);
			}
		}

		if (remapperArray[0] != null) {
			remapperArray[0].finish();
		}

		if (!minecraftMappedJar.exists()) {
			throw new RuntimeException("mapped jar not found");
		}

		addDependencies(dependency, postPopulationScheduler);

		if (getExtension().isForge()) {
			getProject().getDependencies().add(Constants.Configurations.FORGE_NAMED,
					getProject().getDependencies().module("net.minecraftforge-loom:forge:" + getJarVersionString("mapped")));

			getProject().afterEvaluate(project -> {
				if (!OperatingSystem.isCIBuild()) {
					try {
						ForgeSourcesRemapper.addBaseForgeSources(project);
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			});
		}
	}

	private TinyRemapper buildRemapper() throws IOException {
		Path[] libraries = getRemapClasspath(getProject());
		TinyRemapper remapper = getTinyRemapper();
		remapper.readClassPath(libraries);
		remapper.prepareClasses();
		return remapper;
	}
	
	private byte[][] inputBytes(Path input, @Nullable Path assetsOut) throws IOException {
		List<byte[]> inputByteList = new ArrayList<>();

		try (FileSystemUtil.FileSystemDelegate inputFs = FileSystemUtil.getJarFileSystem(input, false)) {
			ThreadingUtils.TaskCompleter taskCompleter = ThreadingUtils.taskCompleter();

			for (Path path : (Iterable<? extends Path>) Files.walk(inputFs.get().getPath("/"))::iterator) {
				if (Files.isRegularFile(path)) {
					if (path.getFileName().toString().endsWith(".class")) {
						taskCompleter.add(() -> {
							byte[] bytes = Files.readAllBytes(path);

							synchronized (inputByteList) {
								inputByteList.add(bytes);
							}
						});
					}
				}
			}

			taskCompleter.complete();
		}

		if (assetsOut != null) {
			try (OutputConsumerPath tmpAssetsPath = new OutputConsumerPath.Builder(assetsOut).assumeArchive(true).build()) {
				if (getExtension().isForge()) {
					tmpAssetsPath.addNonClassFiles(input, NonClassCopyMode.FIX_META_INF, null);
				} else {
					tmpAssetsPath.addNonClassFiles(input);
				}
			}
		}

		return inputByteList.toArray(new byte[0][0]);
	}

	private void mapMinecraftJar(TinyRemapper[] remapperArray) throws Exception {
		Path input = inputJar.toPath();
		Path outputMapped = minecraftMappedJar.toPath();
		Path outputIntermediary = minecraftIntermediaryJar.toPath();
		Path outputSrg = minecraftSrgJar == null ? null : minecraftSrgJar.toPath();
		TinyRemapper remapper = remapperArray[0] = buildRemapper();

		Path assets = Files.createTempFile("assets", null);
		Files.deleteIfExists(assets);
		assets.toFile().deleteOnExit();
		byte[][] inputBytes = inputBytes(input, assets);
		remap(remapper, "minecraft", inputBytes, assets, input, outputMapped,
				outputIntermediary, outputSrg, "official");
	}

	private void mapForgeJar(TinyRemapper[] remapperArray) throws Exception {
		Path input = inputJar.toPath();
		Path inputForge = inputForgeJar.toPath();
		Path outputMapped = forgeMappedJar.toPath();
		Path outputIntermediary = forgeIntermediaryJar.toPath();
		Path outputSrg = forgeSrgJar.toPath();
		TinyRemapper remapper = remapperArray[0] != null ? remapperArray[0] : (remapperArray[0] = buildRemapper());

		Path assets = Files.createTempFile("assets", null);
		Files.deleteIfExists(assets);
		assets.toFile().deleteOnExit();
		byte[][] inputBytes = inputBytes(inputForge, assets);
		remap(remapper, "forge", inputBytes, assets, input, outputMapped,
				outputIntermediary, outputSrg, "official");
	}
	
	public void remap(TinyRemapper remapper, String id, byte[][] inputBytes, Path assets, Path mcOfficialJar,
			Path outputMapped, Path outputIntermediary, Path outputSrg, String fromM) throws IOException {
		for (String toM : getExtension().isForge() ? Arrays.asList("intermediary", "srg", "named") : Arrays.asList("intermediary", "named")) {
			Path output = "named".equals(toM) ? outputMapped : "srg".equals(toM) ? outputSrg : outputIntermediary;
			Stopwatch stopwatch = Stopwatch.createStarted();
			getProject().getLogger().lifecycle(":remapping " + id + " (TinyRemapper, " + fromM + " -> " + toM + ")");

			remapper.readInputs(inputBytes);
			remapper.replaceMappings(getMappings(mcOfficialJar, fromM, toM));
			OutputRemappingHandler.remap(remapper, assets, output);

			getProject().getLogger().lifecycle(":remapped " + id + " (TinyRemapper, " + fromM + " -> " + toM + ") in " + stopwatch);
			remapper.removeInput();

			if (getExtension().isForge() && !"srg".equals(toM)) {
				getProject().getLogger().info(":running " + id + " finalising tasks");

				TinyTree yarnWithSrg = getExtension().getMappingsProvider().getMappingsWithSrg();
				AtRemapper.remap(getProject().getLogger(), output, yarnWithSrg);
				CoreModClassRemapper.remapJar(output, yarnWithSrg, getProject().getLogger());
			}
		}
	}

	public TinyRemapper getTinyRemapper() throws IOException {
		TinyRemapper.Builder builder = TinyRemapper.newRemapper()
				.renameInvalidLocals(true)
				.logUnknownInvokeDynamic(false)
				.ignoreConflicts(getExtension().isForge())
				.cacheMappings(true)
				.threads(Runtime.getRuntime().availableProcessors())
				.logger(getProject().getLogger()::lifecycle)
				.rebuildSourceFilenames(true);

		if (getExtension().isForge()) {
			/* FORGE: Required for classes like aej$OptionalNamedTag (1.16.4) which are added by Forge patches.
			 * They won't get remapped to their proper packages, so IllegalAccessErrors will happen without ._.
			 */
			builder.fixPackageAccess(true);
		}

		return builder.build();
	}

	public Set<IMappingProvider> getMappings(@Nullable Path fromJar, String fromM, String toM) throws IOException {
		Set<IMappingProvider> providers = new HashSet<>();
		providers.add(TinyRemapperMappingsHelper.create(getExtension().isForge() ? getExtension().getMappingsProvider().getMappingsWithSrg() : getExtension().getMappingsProvider().getMappings(), fromM, toM, true));

		if (getExtension().isForge()) {
			if (fromJar != null) {
				providers.add(InnerClassRemapper.of(fromJar, getExtension().getMappingsProvider().getMappingsWithSrg(), fromM, toM));
			}
		} else {
			providers.add(out -> JSR_TO_JETBRAINS.forEach(out::acceptClass));
		}

		return providers;
	}

	public static Path[] getRemapClasspath(Project project) {
		return project.getConfigurations().getByName(Constants.Configurations.MINECRAFT_DEPENDENCIES).getFiles()
				.stream().map(File::toPath).toArray(Path[]::new);
	}

	protected void addDependencies(DependencyInfo dependency, Consumer<Runnable> postPopulationScheduler) {
		getProject().getDependencies().add(Constants.Configurations.MINECRAFT_NAMED,
				getProject().getDependencies().module("net.minecraft:minecraft:" + getJarVersionString("mapped")));
	}

	public void initFiles(MinecraftProviderImpl minecraftProvider, MappingsProviderImpl mappingsProvider) {
		this.minecraftProvider = minecraftProvider;
		minecraftIntermediaryJar = new File(getExtension().getUserCache(), "minecraft-" + getJarVersionString("intermediary") + ".jar");
		minecraftSrgJar = !getExtension().isForge() ? null : new File(getExtension().getUserCache(), "minecraft-" + getJarVersionString("srg") + ".jar");
		minecraftMappedJar = new File(getJarDirectory(getExtension().getUserCache(), "mapped"), "minecraft-" + getJarVersionString("mapped") + ".jar");
		inputJar = getExtension().isForge() ? mappingsProvider.patchedProvider.getMergedJar() : minecraftProvider.getMergedJar();
		if (getExtension().isForge()) {
			inputForgeJar = mappingsProvider.patchedProvider.getForgeMergedJar();
			forgeIntermediaryJar = new File(getExtension().getUserCache(), "forge-" + getJarVersionString("intermediary") + ".jar");
			forgeSrgJar = new File(getExtension().getUserCache(), "forge-" + getJarVersionString("srg") + ".jar");
			forgeMappedJar = new File(getJarDirectory(getExtension().getUserCache(), "mapped"), "forge-" + getJarVersionString("mapped") + ".jar");
		}
	}

	protected File getJarDirectory(File parentDirectory, String type) {
		return new File(parentDirectory, getJarVersionString(type));
	}

	protected String getJarVersionString(String type) {
		return String.format("%s-%s-%s-%s%s", minecraftProvider.minecraftVersion(), type, getExtension().getMappingsProvider().mappingsName, getExtension().getMappingsProvider().mappingsVersion, minecraftProvider.getJarSuffix());
	}

	public File getIntermediaryJar() {
		return minecraftIntermediaryJar;
	}

	public File getSrgJar() {
		return minecraftSrgJar;
	}

	public File getMappedJar() {
		return minecraftMappedJar;
	}

	public File getForgeIntermediaryJar() {
		return forgeIntermediaryJar;
	}

	public File getForgeSrgJar() {
		return forgeSrgJar;
	}

	public File getForgeMappedJar() {
		return forgeMappedJar;
	}

	public File getUnpickedJar() {
		return new File(getJarDirectory(getExtension().getUserCache(), "mapped"), "minecraft-" + getJarVersionString("unpicked") + ".jar");
	}

	@Override
	public String getTargetConfig() {
		return Constants.Configurations.MINECRAFT_NAMED;
	}
}
