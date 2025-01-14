package org.rdfhdt.hdt.dictionary.impl.section;

import org.rdfhdt.hdt.compact.integer.VByte;
import org.rdfhdt.hdt.compact.sequence.SequenceLog64BigDisk;
import org.rdfhdt.hdt.dictionary.DictionarySectionPrivate;
import org.rdfhdt.hdt.dictionary.TempDictionarySection;
import org.rdfhdt.hdt.exceptions.NotImplementedException;
import org.rdfhdt.hdt.listener.MultiThreadListener;
import org.rdfhdt.hdt.listener.ProgressListener;
import org.rdfhdt.hdt.options.HDTOptions;
import org.rdfhdt.hdt.util.crc.CRC32;
import org.rdfhdt.hdt.util.crc.CRC8;
import org.rdfhdt.hdt.util.crc.CRCOutputStream;
import org.rdfhdt.hdt.util.io.CloseSuppressPath;
import org.rdfhdt.hdt.util.io.CountOutputStream;
import org.rdfhdt.hdt.util.io.IOUtil;
import org.rdfhdt.hdt.util.listener.ListenerUtil;
import org.rdfhdt.hdt.util.string.ByteString;
import org.rdfhdt.hdt.util.string.ByteStringUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/**
 * Implementation of {@link org.rdfhdt.hdt.dictionary.DictionarySectionPrivate} that write loaded
 * {@link org.rdfhdt.hdt.dictionary.TempDictionarySection} on disk before saving, reducing the size in ram,
 * iterator should be a byte string
 *
 * @author Antoine Willerval
 */
public class WriteDictionarySection implements DictionarySectionPrivate {
	private final CloseSuppressPath tempFilename;
	private final CloseSuppressPath blockTempFilename;
	private SequenceLog64BigDisk blocks;
	private final long blockSize;
	private final int bufferSize;
	private long numberElements = 0;
	private long byteoutSize;

	public WriteDictionarySection(HDTOptions spec, Path filename, int bufferSize) {
		this.bufferSize = bufferSize;
		String fn = filename.getFileName().toString();
		tempFilename = CloseSuppressPath.of(filename.resolveSibling(fn + "_temp"));
		blockTempFilename = CloseSuppressPath.of(filename.resolveSibling(fn + "_tempblock"));
		blockSize = spec.getInt("pfc.blocksize", PFCDictionarySection.DEFAULT_BLOCK_SIZE);
		if (blockSize < 0) {
			throw new IllegalArgumentException("negative pfc.blocksize");
		}
	}

	@Override
	public void load(TempDictionarySection other, ProgressListener plistener) {
		load(other.getSortedEntries(), other.getNumberOfElements(), plistener);
	}

	public void load(Iterator<? extends CharSequence> it, long count, ProgressListener plistener) {
		MultiThreadListener listener = ListenerUtil.multiThreadListener(plistener);
		long block = count < 10 ? 1 : count / 10;
		long currentCount = 0;
		blocks = new SequenceLog64BigDisk(blockTempFilename.toAbsolutePath().toString(), 64, count / blockSize);

		listener.notifyProgress(0, "Filling section");
		try (CountOutputStream out = new CountOutputStream(tempFilename.openOutputStream(bufferSize))) {
			CRCOutputStream crcout = new CRCOutputStream(out, new CRC32());
			ByteString previousStr = null;
			for (; it.hasNext(); currentCount++) {
				ByteString str = (ByteString) (it.next());
				assert str != null;
				if (numberElements % blockSize == 0) {
					blocks.append(out.getTotalBytes());

					// Copy full string
					ByteStringUtil.append(crcout, str, 0);
				} else {
					// Find common part.
					int delta = ByteStringUtil.longestCommonPrefix(previousStr, str);
					// Write Delta in VByte
					VByte.encode(crcout, delta);
					// Write remaining
					ByteStringUtil.append(crcout, str, delta);
				}
				crcout.write(0);
				previousStr = str;
				numberElements++;
				if (currentCount % block == 0) {
					listener.notifyProgress((float) (currentCount * 100 / count), "Filling section");
				}
			}

			byteoutSize = out.getTotalBytes();
			crcout.writeCRC();
		} catch (IOException e) {
			throw new RuntimeException("can't load section", e);
		}
		blocks.append(byteoutSize);
		// Trim text/blocks
		blocks.aggressiveTrimToSize();
		if (numberElements % 100_000 == 0) {
			listener.notifyProgress(100, "Completed section filling");
		}
	}

	@Override
	public void save(OutputStream output, ProgressListener listener) throws IOException {
		CRCOutputStream out = new CRCOutputStream(output, new CRC8());
		out.write(PFCDictionarySection.TYPE_INDEX);
		VByte.encode(out, numberElements);

		VByte.encode(out, byteoutSize);
		VByte.encode(out, blockSize);
		out.writeCRC();
		// Write blocks directly to output, they have their own CRC check.
		blocks.save(output, listener);
		// Write blocks data directly to output, the load was writing using a CRC check.
		Files.copy(tempFilename, output);
	}

	@Override
	public void load(InputStream input, ProgressListener listener) throws IOException {
		throw new NotImplementedException();
	}

	@Override
	public long locate(CharSequence s) {
		throw new NotImplementedException();
	}

	@Override
	public CharSequence extract(long pos) {
		throw new NotImplementedException();
	}

	@Override
	public long size() {
		return numberElements;
	}

	@Override
	public long getNumberOfElements() {
		return numberElements;
	}

	@Override
	public Iterator<? extends CharSequence> getSortedEntries() {
		throw new NotImplementedException();
	}

	@Override
	public void close() throws IOException {
		IOUtil.closeAll(blocks, tempFilename, blockTempFilename);
	}
}
