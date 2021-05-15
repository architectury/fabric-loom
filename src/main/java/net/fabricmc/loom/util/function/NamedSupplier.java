package net.fabricmc.loom.util.function;

import java.util.function.Supplier;

@FunctionalInterface
public interface NamedSupplier<T> extends Supplier<T> {

	default String getName() {
		return null;
	}
}
