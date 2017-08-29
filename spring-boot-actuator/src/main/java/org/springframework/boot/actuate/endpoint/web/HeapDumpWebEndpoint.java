/*
 * Copyright 2012-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.actuate.endpoint.web;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.PlatformManagedObject;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.endpoint.Endpoint;
import org.springframework.boot.endpoint.EndpointExposure;
import org.springframework.boot.endpoint.ReadOperation;
import org.springframework.boot.endpoint.web.WebEndpointResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Web {@link Endpoint} to expose heap dumps.
 *
 * @author Lari Hotari
 * @author Phillip Webb
 * @author Raja Kolli
 * @author Andy Wilkinson
 * @since 2.0.0
 */
@ConfigurationProperties(prefix = "endpoints.heapdump")
@Endpoint(id = "heapdump", exposure = EndpointExposure.WEB)
public class HeapDumpWebEndpoint {

	private final long timeout;

	private final Lock lock = new ReentrantLock();

	private HeapDumper heapDumper;

	public HeapDumpWebEndpoint() {
		this(TimeUnit.SECONDS.toMillis(10));
	}

	protected HeapDumpWebEndpoint(long timeout) {
		this.timeout = timeout;
	}

	@ReadOperation
	public WebEndpointResponse<Resource> heapDump(Boolean live) {
		try {
			if (this.lock.tryLock(this.timeout, TimeUnit.MILLISECONDS)) {
				try {
					return new WebEndpointResponse<>(
							dumpHeap(live == null ? true : live));
				}
				finally {
					this.lock.unlock();
				}
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		catch (IOException ex) {
			return new WebEndpointResponse<>(HttpStatus.INTERNAL_SERVER_ERROR.value());
		}
		catch (HeapDumperUnavailableException ex) {
			return new WebEndpointResponse<>(HttpStatus.SERVICE_UNAVAILABLE.value());
		}
		return new WebEndpointResponse<>(HttpStatus.TOO_MANY_REQUESTS.value());
	}

	private Resource dumpHeap(boolean live) throws IOException, InterruptedException {
		if (this.heapDumper == null) {
			this.heapDumper = createHeapDumper();
		}
		File file = createTempFile(live);
		this.heapDumper.dumpHeap(file, live);
		return new TemporaryFileSystemResource(file);
	}

	private File createTempFile(boolean live) throws IOException {
		String date = new SimpleDateFormat("yyyy-MM-dd-HH-mm").format(new Date());
		File file = File.createTempFile("heapdump" + date + (live ? "-live" : ""),
				".hprof");
		file.delete();
		return file;
	}

	/**
	 * Factory method used to create the {@link HeapDumper}.
	 * @return the heap dumper to use
	 * @throws HeapDumperUnavailableException if the heap dumper cannot be created
	 */
	protected HeapDumper createHeapDumper() throws HeapDumperUnavailableException {
		return new HotSpotDiagnosticMXBeanHeapDumper();
	}

	/**
	 * Strategy interface used to dump the heap to a file.
	 */
	@FunctionalInterface
	protected interface HeapDumper {

		/**
		 * Dump the current heap to the specified file.
		 * @param file the file to dump the heap to
		 * @param live if only <em>live</em> objects (i.e. objects that are reachable from
		 * others) should be dumped
		 * @throws IOException on IO error
		 * @throws InterruptedException on thread interruption
		 */
		void dumpHeap(File file, boolean live) throws IOException, InterruptedException;

	}

	/**
	 * {@link HeapDumper} that uses {@code com.sun.management.HotSpotDiagnosticMXBean}
	 * available on Oracle and OpenJDK to dump the heap to a file.
	 */
	protected static class HotSpotDiagnosticMXBeanHeapDumper implements HeapDumper {

		private Object diagnosticMXBean;

		private Method dumpHeapMethod;

		@SuppressWarnings("unchecked")
		protected HotSpotDiagnosticMXBeanHeapDumper() {
			try {
				Class<?> diagnosticMXBeanClass = ClassUtils.resolveClassName(
						"com.sun.management.HotSpotDiagnosticMXBean", null);
				this.diagnosticMXBean = ManagementFactory.getPlatformMXBean(
						(Class<PlatformManagedObject>) diagnosticMXBeanClass);
				this.dumpHeapMethod = ReflectionUtils.findMethod(diagnosticMXBeanClass,
						"dumpHeap", String.class, Boolean.TYPE);
			}
			catch (Throwable ex) {
				throw new HeapDumperUnavailableException(
						"Unable to locate HotSpotDiagnosticMXBean", ex);
			}
		}

		@Override
		public void dumpHeap(File file, boolean live) {
			ReflectionUtils.invokeMethod(this.dumpHeapMethod, this.diagnosticMXBean,
					file.getAbsolutePath(), live);
		}

	}

	/**
	 * Exception to be thrown if the {@link HeapDumper} cannot be created.
	 */
	protected static class HeapDumperUnavailableException extends RuntimeException {

		public HeapDumperUnavailableException(String message, Throwable cause) {
			super(message, cause);
		}

	}

	private static final class TemporaryFileSystemResource extends FileSystemResource {

		private final Log logger = LogFactory.getLog(getClass());

		private TemporaryFileSystemResource(File file) {
			super(file);
		}

		@Override
		public ReadableByteChannel readableChannel() throws IOException {
			ReadableByteChannel readableChannel = super.readableChannel();
			return new ReadableByteChannel() {

				@Override
				public boolean isOpen() {
					return readableChannel.isOpen();
				}

				@Override
				public void close() throws IOException {
					try {
						readableChannel.close();
					}
					finally {
						deleteFile();
					}
				}

				@Override
				public int read(ByteBuffer dst) throws IOException {
					return readableChannel.read(dst);
				}

			};
		}

		@Override
		public InputStream getInputStream() throws IOException {
			InputStream delegate = super.getInputStream();
			return new InputStream() {

				@Override
				public int read() throws IOException {
					return delegate.read();
				}

				@Override
				public int read(byte[] b) throws IOException {
					return delegate.read(b);
				}

				@Override
				public int read(byte[] b, int off, int len) throws IOException {
					return delegate.read(b, off, len);
				}

				@Override
				public long skip(long n) throws IOException {
					return delegate.skip(n);
				}

				@Override
				public int available() throws IOException {
					return delegate.available();
				}

				@Override
				public void close() throws IOException {
					try {
						delegate.close();
					}
					finally {
						deleteFile();
					}
				}

				@Override
				public synchronized void mark(int readlimit) {
					delegate.mark(readlimit);
				}

				@Override
				public synchronized void reset() throws IOException {
					delegate.reset();
				}

				@Override
				public boolean markSupported() {
					return delegate.markSupported();
				}

			};
		}

		private void deleteFile() {
			try {
				Files.delete(getFile().toPath());
			}
			catch (IOException ex) {
				TemporaryFileSystemResource.this.logger.warn(
						"Failed to delete temporary heap dump file '" + getFile() + "'",
						ex);
			}
		}

		@Override
		public boolean isFile() {
			// Prevent zero-copy so we can delete the file on close
			return false;
		}

	}

}
