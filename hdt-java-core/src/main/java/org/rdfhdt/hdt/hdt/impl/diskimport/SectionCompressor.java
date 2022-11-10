package org.rdfhdt.hdt.hdt.impl.diskimport;

import org.rdfhdt.hdt.iterator.utils.AsyncIteratorFetcher;
import org.rdfhdt.hdt.iterator.utils.SizeFetcher;
import org.rdfhdt.hdt.listener.MultiThreadListener;
import org.rdfhdt.hdt.triples.IndexedNode;
import org.rdfhdt.hdt.triples.TripleString;
import org.rdfhdt.hdt.util.ParallelSortableArrayList;
import org.rdfhdt.hdt.util.concurrent.ExceptionFunction;
import org.rdfhdt.hdt.util.concurrent.ExceptionSupplier;
import org.rdfhdt.hdt.util.concurrent.ExceptionThread;
import org.rdfhdt.hdt.util.concurrent.KWayMerger;
import org.rdfhdt.hdt.util.io.CloseSuppressPath;
import org.rdfhdt.hdt.util.io.IOUtil;
import org.rdfhdt.hdt.util.io.compress.CompressNodeMergeIterator;
import org.rdfhdt.hdt.util.io.compress.CompressNodeReader;
import org.rdfhdt.hdt.util.io.compress.CompressUtil;
import org.rdfhdt.hdt.util.listener.IntermediateListener;
import org.rdfhdt.hdt.util.string.ByteString;
import org.rdfhdt.hdt.util.string.CompactString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Tree worker object to compress the section of a triple stream into 3 sections (SPO) and a compress triple file
 *
 * @author Antoine Willerval
 */
public class SectionCompressor implements KWayMerger.KWayMergerImpl<TripleString, SizeFetcher<TripleString>> {
	private static final Logger log = LoggerFactory.getLogger(SectionCompressor.class);

	private final CloseSuppressPath baseFileName;
	private final AsyncIteratorFetcher<TripleString> source;
	private final MultiThreadListener listener;
	private final AtomicLong triples = new AtomicLong();
	private final AtomicLong ntRawSize = new AtomicLong();
	private final int bufferSize;
	private final long chunkSize;
	private final int k;
	private final boolean debugSleepKwayDict;

	public SectionCompressor(CloseSuppressPath baseFileName, AsyncIteratorFetcher<TripleString> source, MultiThreadListener listener, int bufferSize, long chunkSize, int k, boolean debugSleepKwayDict) {
		this.source = source;
		this.listener = listener;
		this.baseFileName = baseFileName;
		this.bufferSize = bufferSize;
		this.chunkSize = chunkSize;
		this.k = k;
		this.debugSleepKwayDict = debugSleepKwayDict;
	}

	/*
	 * FIXME: create a factory and override these methods with the hdt spec
	 */

	/**
	 * mapping method for the subject of the triple, this method should copy the sequence!
	 *
	 * @param seq the subject (before)
	 * @return the subject mapped
	 */
	protected ByteString convertSubject(CharSequence seq) {
		return new CompactString(seq);
	}

	/**
	 * mapping method for the predicate of the triple, this method should copy the sequence!
	 *
	 * @param seq the predicate (before)
	 * @return the predicate mapped
	 */
	protected ByteString convertPredicate(CharSequence seq) {
		return new CompactString(seq);
	}

	/**
	 * mapping method for the object of the triple, this method should copy the sequence!
	 *
	 * @param seq the object (before)
	 * @return the object mapped
	 */
	protected ByteString convertObject(CharSequence seq) {
		return new CompactString(seq);
	}

	/**
	 * Compress the stream into complete pre-sections files
	 *
	 * @param workers      the number of workers
	 * @return compression result
	 * @throws IOException                    io exception
	 * @throws InterruptedException           if the thread is interrupted
	 * @throws KWayMerger.KWayMergerException exception with the tree working
	 * @see #compressPartial()
	 * @see #compress(int, String)
	 */
	public CompressionResult compressToFile(int workers) throws IOException, InterruptedException, KWayMerger.KWayMergerException {
		// force to create the first file
		KWayMerger<TripleString, SizeFetcher<TripleString>> merger = new KWayMerger<>(baseFileName, source, this, Math.max(1, workers - 1), k);
		merger.start();
		// wait for the workers to merge the sections and create the triples
		Optional<CloseSuppressPath> sections = merger.waitResult();
		if (sections.isEmpty()) {
			return new CompressionResultEmpty();
		}
		return new CompressionResultFile(triples.get(), ntRawSize.get(), new TripleFile(sections.get(), false));
	}

	/**
	 * Compress the stream into multiple pre-sections files and merge them on the fly
	 *
	 * @return compression result
	 * @throws IOException io exception
	 * @see #compressToFile(int)
	 * @see #compress(int, String)
	 */
	public CompressionResult compressPartial() throws IOException, KWayMerger.KWayMergerException {
		List<TripleFile> files = new ArrayList<>();
		baseFileName.closeWithDeleteRecurse();
		try {
			baseFileName.mkdirs();
			long fileName = 0;
			while (!source.isEnd()) {
				TripleFile file = new TripleFile(baseFileName.resolve("chunk#"+fileName++), true);
				createChunk(newStopFlux(source), file.root);
				files.add(file);
			}
		} catch (Throwable e) {
			try {
				throw e;
			} finally {
				try {
					IOUtil.closeAll(files);
				} finally {
					baseFileName.close();
				}
			}
		}
		return new CompressionResultPartial(files, triples.get(), ntRawSize.get());
	}

	/**
	 * compress the sections/triples with a particular mode
	 *
	 * @param workers      the worker required
	 * @param mode         the mode to compress, can be {@link org.rdfhdt.hdt.hdt.impl.diskimport.CompressionResult#COMPRESSION_MODE_COMPLETE} (default), {@link org.rdfhdt.hdt.hdt.impl.diskimport.CompressionResult#COMPRESSION_MODE_PARTIAL} or null/"" for default
	 * @return the compression result
	 * @throws KWayMerger.KWayMergerException tree working exception
	 * @throws IOException                    io exception
	 * @throws InterruptedException           thread interruption
	 * @see #compressToFile(int)
	 * @see #compressPartial()
	 */
	public CompressionResult compress(int workers, String mode) throws KWayMerger.KWayMergerException, IOException, InterruptedException {
		if (mode == null) {
			mode = "";
		}
		switch (mode) {
			case "":
			case CompressionResult.COMPRESSION_MODE_COMPLETE:
				return compressToFile(workers);
			case CompressionResult.COMPRESSION_MODE_PARTIAL:
				return compressPartial();
			default:
				throw new IllegalArgumentException("Unknown compression mode: " + mode);
		}
	}

	@Override
	public void createChunk(SizeFetcher<TripleString> fetcher, CloseSuppressPath output) throws KWayMerger.KWayMergerException {

		listener.notifyProgress(0, "start reading triples");

		ParallelSortableArrayList<IndexedNode> subjects = new ParallelSortableArrayList<>(IndexedNode[].class);
		ParallelSortableArrayList<IndexedNode> predicates = new ParallelSortableArrayList<>(IndexedNode[].class);
		ParallelSortableArrayList<IndexedNode> objects = new ParallelSortableArrayList<>(IndexedNode[].class);

		listener.notifyProgress(10, "reading triples " + triples.get());
		TripleString next;
		while ((next = fetcher.get()) != null) {

			if (debugSleepKwayDict) {
				try {
					Thread.sleep(25);
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
			}

			// load the map triple and write it in the writer
			long tripleID = triples.incrementAndGet();

			// get indexed mapped char sequence
			IndexedNode subjectNode = new IndexedNode(
					convertSubject(next.getSubject()),
					tripleID
			);
			subjects.add(subjectNode);

			// get indexed mapped char sequence
			IndexedNode predicateNode = new IndexedNode(
					convertPredicate(next.getPredicate()),
					tripleID
			);
			predicates.add(predicateNode);

			// get indexed mapped char sequence
			IndexedNode objectNode = new IndexedNode(
					convertObject(next.getObject()),
					tripleID
			);
			objects.add(objectNode);


			if (tripleID % 100_000 == 0) {
				listener.notifyProgress(10, "reading triples " + tripleID);
			}
			// too much ram allowed?
			if (subjects.size() == Integer.MAX_VALUE - 6) {
				break;
			}
		}

		ntRawSize.addAndGet(fetcher.getSize());

		try {
			TripleFile sections = new TripleFile(output, true);
			try {
				IntermediateListener il = new IntermediateListener(listener);
				il.setRange(70, 80);
				il.setPrefix("creating subjects section " + sections.root.getFileName() + ": ");
				il.notifyProgress(0, "sorting");
				try (OutputStream stream = sections.openWSubject()) {
					subjects.parallelSort(IndexedNode::compareTo);
					CompressUtil.writeCompressedSection(subjects, stream, il);
				}
				il.setRange(80, 90);
				il.setPrefix("creating predicates section " + sections.root.getFileName() + ": ");
				il.notifyProgress(0, "sorting");
				try (OutputStream stream = sections.openWPredicate()) {
					predicates.parallelSort(IndexedNode::compareTo);
					CompressUtil.writeCompressedSection(predicates, stream, il);
				}
				il.setRange(90, 100);
				il.setPrefix("creating objects section " + sections.root.getFileName() + ": ");
				il.notifyProgress(0, "sorting");
				try (OutputStream stream = sections.openWObject()) {
					objects.parallelSort(IndexedNode::compareTo);
					CompressUtil.writeCompressedSection(objects, stream, il);
				}
			} finally {
				subjects.clear();
				predicates.clear();
				objects.clear();
				listener.notifyProgress(100, "section completed" + sections.root.getFileName().toString());
			}
		} catch (IOException e) {
			throw new KWayMerger.KWayMergerException(e);
		}
	}

	@Override
	public void mergeChunks(List<CloseSuppressPath> inputs, CloseSuppressPath output) throws KWayMerger.KWayMergerException {
		TripleFile sections;
		try {
			sections = new TripleFile(output, true);
			List<TripleFile> tripleFiles = new ArrayList<>();
			for (CloseSuppressPath in : inputs) {
				tripleFiles.add(new TripleFile(in, false));
			}
			sections.compute(tripleFiles, false);
			listener.notifyProgress(100, "sections merged " + sections.root.getFileName());
			// delete old sections
			IOUtil.closeAll(inputs);
		} catch (IOException | InterruptedException e) {
			throw new KWayMerger.KWayMergerException(e);
		}
	}

	@Override
	public SizeFetcher<TripleString> newStopFlux(Supplier<TripleString> flux) {
		return SizeFetcher.ofTripleString(flux, chunkSize);
	}

	/**
	 * A triple directory, contains 3 files, subject, predicate and object
	 *
	 * @author Antoine Willerval
	 */
	public class TripleFile implements Closeable {
		private final CloseSuppressPath root;
		private final CloseSuppressPath s;
		private final CloseSuppressPath p;
		private final CloseSuppressPath o;

		private TripleFile(CloseSuppressPath root, boolean mkdir) throws IOException {
			this.root = root;
			this.s = root.resolve("subject");
			this.p = root.resolve("predicate");
			this.o = root.resolve("object");

			root.closeWithDeleteRecurse();
			if (mkdir) {
				root.mkdirs();
			}
		}

		@Override
		public void close() throws IOException {
			delete();
		}

		public void delete() throws IOException {
			root.close();
		}

		/**
		 * @return open a write stream to the subject file
		 * @throws IOException can't open the stream
		 */
		public OutputStream openWSubject() throws IOException {
			return s.openOutputStream(bufferSize);
		}

		/**
		 * @return open a write stream to the predicate file
		 * @throws IOException can't open the stream
		 */
		public OutputStream openWPredicate() throws IOException {
			return p.openOutputStream(bufferSize);
		}

		/**
		 * @return open a write stream to the object file
		 * @throws IOException can't open the stream
		 */
		public OutputStream openWObject() throws IOException {
			return o.openOutputStream(bufferSize);
		}

		/**
		 * @return open a read stream to the subject file
		 * @throws IOException can't open the stream
		 */
		public InputStream openRSubject() throws IOException {
			return s.openInputStream(bufferSize);
		}

		/**
		 * @return open a read stream to the predicate file
		 * @throws IOException can't open the stream
		 */
		public InputStream openRPredicate() throws IOException {
			return p.openInputStream(bufferSize);
		}

		/**
		 * @return open a read stream to the object file
		 * @throws IOException can't open the stream
		 */
		public InputStream openRObject() throws IOException {
			return o.openInputStream(bufferSize);
		}

		/**
		 * @return the path to the subject file
		 */
		public CloseSuppressPath getSubjectPath() {
			return s;
		}

		/**
		 * @return the path to the predicate file
		 */
		public CloseSuppressPath getPredicatePath() {
			return p;
		}

		/**
		 * @return the path to the object file
		 */
		public CloseSuppressPath getObjectPath() {
			return o;
		}

		/**
		 * compute this triple file from multiple triples files
		 *
		 * @param triples triples files container
		 * @param async   if the method should load all the files asynchronously or not
		 * @throws IOException          io exception while reading/writing
		 * @throws InterruptedException interruption while waiting for the async thread
		 */
		public void compute(List<TripleFile> triples, boolean async) throws IOException, InterruptedException {
			if (!async) {
				computeSubject(triples, false);
				computePredicate(triples, false);
				computeObject(triples, false);
			} else {
				ExceptionThread.async("SectionMerger" + root.getFileName(),
						() -> computeSubject(triples, true),
						() -> computePredicate(triples, true),
						() -> computeObject(triples,true)
				).joinAndCrashIfRequired();
			}
		}

		private void computeSubject(List<TripleFile> triples, boolean async) throws IOException {
			computeSection(triples, "subject", 0, 33, this::openWSubject, TripleFile::openRSubject, TripleFile::getSubjectPath, async);
		}

		private void computePredicate(List<TripleFile> triples, boolean async) throws IOException {
			computeSection(triples, "predicate", 33, 66, this::openWPredicate, TripleFile::openRPredicate, TripleFile::getPredicatePath, async);
		}

		private void computeObject(List<TripleFile> triples, boolean async) throws IOException {
			computeSection(triples, "object", 66, 100, this::openWObject, TripleFile::openRObject, TripleFile::getObjectPath, async);
		}

		private void computeSection(List<TripleFile> triples, String section, int start, int end, ExceptionSupplier<OutputStream, IOException> openW, ExceptionFunction<TripleFile, InputStream, IOException> openR, Function<TripleFile, Closeable> fileDelete, boolean async) throws IOException {
			IntermediateListener il = new IntermediateListener(listener);
			if (async) {
				listener.registerThread(Thread.currentThread().getName());
			} else {
				il.setRange(start, end);
			}
			il.setPrefix("merging " + section + " section " + root.getFileName() + ": ");
			il.notifyProgress(0, "merging section");

			// readers to create the merge tree
			CompressNodeReader[] readers = new CompressNodeReader[triples.size()];
			Closeable[] fileDeletes = new Closeable[triples.size()];
			try {
				long size = 0L;
				for (int i = 0; i < triples.size(); i++) {
					CompressNodeReader reader = new CompressNodeReader(openR.apply(triples.get(i)));
					size += reader.getSize();
					readers[i] = reader;
					fileDeletes[i] = fileDelete.apply(triples.get(i));
				}

				// section
				try (OutputStream output = openW.get()) { // IndexNodeDeltaMergeExceptionIterator
					CompressUtil.writeCompressedSection(CompressNodeMergeIterator.buildOfTree(readers), size, output, il);
				}
			} finally {
				if (async) {
					listener.unregisterThread(Thread.currentThread().getName());
				}
				try {
					IOUtil.closeAll(readers);
				} finally {
					IOUtil.closeAll(fileDeletes);
				}
			}
		}
	}

}
