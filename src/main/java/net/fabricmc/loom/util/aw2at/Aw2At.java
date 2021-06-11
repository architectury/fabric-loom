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

package net.fabricmc.loom.util.aw2at;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.cadixdev.at.AccessChange;
import org.cadixdev.at.AccessTransform;
import org.cadixdev.at.AccessTransformSet;
import org.cadixdev.at.ModifierChange;
import org.cadixdev.bombe.type.signature.MethodSignature;

/**
 * Converts AW to AT.
 *
 * @author Juuz
 */
public final class Aw2At {
	public static AccessTransformSet toAccessTransformSet(InputStream in) throws IOException {
		AccessTransformSet atSet = AccessTransformSet.create();

		try (AccessWidenerReader reader = new AccessWidenerReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			reader.accept(new AccessWidenerReader.Visitor() {
				@Override
				public void visitClass(String className, AccessWidenerReader.ClassAccess access) {
					atSet.getOrCreateClass(className).merge(toAt(access));
				}

				@Override
				public void visitMethod(String className, String methodName, String descriptor, AccessWidenerReader.MethodAccess access) {
					atSet.getOrCreateClass(className).mergeMethod(MethodSignature.of(methodName, descriptor), toAt(access));
				}

				@Override
				public void visitField(String className, String fieldName, String descriptor, AccessWidenerReader.FieldAccess access) {
					atSet.getOrCreateClass(className).mergeField(fieldName, toAt(access));
				}
			});
		}

		return atSet;
	}

	private static AccessTransform toAt(AccessWidenerReader.ClassAccess access) {
		return switch (access) {
		case ACCESSIBLE -> AccessTransform.of(AccessChange.PUBLIC);
		case EXTENDABLE -> AccessTransform.of(AccessChange.PUBLIC, ModifierChange.REMOVE);
		};
	}

	private static AccessTransform toAt(AccessWidenerReader.MethodAccess access) {
		return switch (access) {
		// FIXME: This behaviour doesn't match what the actual AW does.
		//   - accessible makes the method final if it was private
		//   - extendable makes the method protected if it was (package-)private
		//   Neither of these can be achieved with Forge ATs without using bytecode analysis.
		case ACCESSIBLE -> AccessTransform.of(AccessChange.PUBLIC);
		case EXTENDABLE -> AccessTransform.of(AccessChange.PUBLIC, ModifierChange.REMOVE);
		};
	}

	private static AccessTransform toAt(AccessWidenerReader.FieldAccess access) {
		return switch (access) {
		case ACCESSIBLE -> AccessTransform.of(AccessChange.PUBLIC);
		case MUTABLE -> AccessTransform.of(ModifierChange.REMOVE);
		};
	}
}
