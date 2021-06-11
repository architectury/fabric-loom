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

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.util.Arrays;

/**
 * A visitor-based reader for access wideners.
 *
 * @author Juuz, with base code from FabricMC/access-widener, licensed under the Apache License 2.0.
 */
public final class AccessWidenerReader implements Closeable {
	private final BufferedReader reader;

	public AccessWidenerReader(Reader reader) {
		this.reader = reader instanceof BufferedReader br ? br : new BufferedReader(reader);
	}

	public void accept(Visitor visitor) throws IOException {
		String[] header = reader.readLine().split("\\s+");

		if (header.length != 3) {
			throw new IOException("Header does not have three components: " + Arrays.toString(header));
		} else if (!header[0].equals("accessWidener")) {
			throw new IOException("Not an accessWidener");
		} else if (!header[1].equals("v1")) {
			throw new UnsupportedOperationException("Unsupported AW version: " + header[1]);
		}

		visitor.visitHeader(header[2]);
		String line;

		while ((line = reader.readLine()) != null) {
			int comment = line.indexOf('#');

			if (comment >= 0) {
				line = line.substring(0, comment);
			}

			if (line.isBlank()) continue;
			line = line.trim();

			String[] components = line.split("\\s+");

			switch (components[1]) {
			case "class" -> {
				if (components.length != 3) {
					throw new IOException("Invalid length for class entry: " + components.length);
				}

				// <access> class <name>
				visitor.visitClass(components[2], parseClassAccess(components[0]));
			}

			case "method" -> {
				if (components.length != 5) {
					throw new IOException("Invalid length for method entry: " + components.length);
				}

				// <access> method <class name> <method name> <desc>
				visitor.visitMethod(components[2], components[3], components[4], parseMethodAccess(components[0]));
			}

			case "field" -> {
				if (components.length != 5) {
					throw new IOException("Invalid length for field entry: " + components.length);
				}

				// <access> field <class name> <field name> <desc>
				visitor.visitField(components[2], components[3], components[4], parseFieldAccess(components[0]));
			}

			default -> throw new IOException("Unknown type: " + components[1]);
			}
		}
	}

	private static ClassAccess parseClassAccess(String str) {
		return switch (str) {
		case "accessible" -> ClassAccess.ACCESSIBLE;
		case "extendable" -> ClassAccess.EXTENDABLE;
		default -> throw new IllegalArgumentException("Unknown class access: " + str);
		};
	}

	private static MethodAccess parseMethodAccess(String str) {
		return switch (str) {
		case "accessible" -> MethodAccess.ACCESSIBLE;
		case "extendable" -> MethodAccess.EXTENDABLE;
		default -> throw new IllegalArgumentException("Unknown method access: " + str);
		};
	}

	private static FieldAccess parseFieldAccess(String str) {
		return switch (str) {
		case "accessible" -> FieldAccess.ACCESSIBLE;
		case "mutable" -> FieldAccess.MUTABLE;
		default -> throw new IllegalArgumentException("Unknown field access: " + str);
		};
	}

	@Override
	public void close() throws IOException {
		reader.close();
	}

	public enum ClassAccess {
		ACCESSIBLE,
		EXTENDABLE;
	}

	public enum MethodAccess {
		ACCESSIBLE,
		EXTENDABLE;
	}

	public enum FieldAccess {
		ACCESSIBLE,
		MUTABLE;
	}

	public interface Visitor {
		default void visitHeader(String namespace) {
		}

		default void visitClass(String className, ClassAccess access) {
		}

		default void visitMethod(String className, String methodName, String descriptor, MethodAccess access) {
		}

		default void visitField(String className, String fieldName, String descriptor, FieldAccess access) {
		}
	}
}
