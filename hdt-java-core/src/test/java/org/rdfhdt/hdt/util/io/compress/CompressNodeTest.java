package org.rdfhdt.hdt.util.io.compress;

import org.junit.Assert;
import org.junit.Test;
import org.rdfhdt.hdt.triples.IndexedNode;
import org.rdfhdt.hdt.util.concurrent.ExceptionThread;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Arrays;
import java.util.List;

public class CompressNodeTest {

	@Test
	public void writeReadTest() throws InterruptedException, IOException {
		PipedOutputStream out = new PipedOutputStream();
		PipedInputStream in = new PipedInputStream();
		out.connect(in);
		List<IndexedNode> nodes = Arrays.asList(
				new IndexedNode("bob", 1),
				new IndexedNode("charles", 6),
				new IndexedNode("jack", 2),
				new IndexedNode("michel", 3)
				);
		new ExceptionThread(() -> {
			CompressNodeReader reader = new CompressNodeReader(in);
			Assert.assertEquals(nodes.size(), reader.getSize());
			try {
				for (IndexedNode excepted : nodes) {
					Assert.assertTrue(reader.hasNext());
					IndexedNode actual = reader.next();
					Assert.assertEquals(excepted.getIndex(), actual.getIndex());
					CompressTest.assertCharSequenceEquals("indexed node", excepted.getNode(), actual.getNode());
				}
				reader.checkComplete();
				Assert.assertEquals(34, in.read());
				Assert.assertEquals(12, in.read());
				Assert.assertEquals(27, in.read());
			} finally {
				in.close();
			}
		}, "ReadTest").attach(
				new ExceptionThread(() -> {
					CompressNodeWriter writer = new CompressNodeWriter(out, nodes.size());
					try {
						for (IndexedNode node : nodes) {
							writer.appendNode(node);
						}
						writer.writeCRC();
						// raw data to check if we didn't read too/not enough data
						out.write(34);
						out.write(12);
						out.write(27);
					} finally {
						out.close();
					}
				}, "WriteTest")
		).startAll().joinAndCrashIfRequired();
	}

	@Test
	public void writeReadUtilTest() throws InterruptedException, IOException {
		PipedOutputStream out = new PipedOutputStream();
		PipedInputStream in = new PipedInputStream();
		out.connect(in);
		List<IndexedNode> nodes = Arrays.asList(
				new IndexedNode("bob", 1),
				new IndexedNode("charles", 6),
				new IndexedNode("jack", 2),
				new IndexedNode("michel", 3)
				);
		new ExceptionThread(() -> {
			CompressNodeReader reader = new CompressNodeReader(in);
			Assert.assertEquals(nodes.size(), reader.getSize());
			try {
				for (IndexedNode excepted : nodes) {
					Assert.assertTrue(reader.hasNext());
					IndexedNode actual = reader.next();
					Assert.assertEquals(excepted.getIndex(), actual.getIndex());
					CompressTest.assertCharSequenceEquals("indexed node", excepted.getNode(), actual.getNode());
				}
				reader.checkComplete();
				Assert.assertEquals(34, in.read());
				Assert.assertEquals(12, in.read());
				Assert.assertEquals(27, in.read());
			} finally {
				in.close();
			}
		}, "ReadTest").attach(
				new ExceptionThread(() -> {
					try {
						CompressUtil.writeCompressedSection(nodes, out, null);
						out.write(34);
						out.write(12);
						out.write(27);
					} finally {
						out.close();
					}
				}, "WriteTest")
		).startAll().joinAndCrashIfRequired();
	}

	@Test
	public void writeReadPassTest() throws InterruptedException, IOException {
		PipedOutputStream out = new PipedOutputStream();
		PipedInputStream in = new PipedInputStream();
		out.connect(in);
		List<IndexedNode> nodes = Arrays.asList(
				new IndexedNode("bob", 1),
				new IndexedNode("charles", 6),
				new IndexedNode("jack", 2),
				new IndexedNode("michel", 3)
				);
		new ExceptionThread(() -> {
			CompressNodeReader reader = new CompressNodeReader(in);
			Assert.assertEquals(nodes.size(), reader.getSize());
			try {
				for (IndexedNode excepted : nodes) {
					Assert.assertTrue(reader.hasNext());
					IndexedNode actual = reader.read();
					Assert.assertEquals(excepted.getIndex(), actual.getIndex());
					CompressTest.assertCharSequenceEquals("indexed node", excepted.getNode(), actual.getNode());
					String actual1Node = actual.getNode().toString();
					IndexedNode actual2 = reader.read();
					Assert.assertEquals(actual.getIndex(), actual2.getIndex());
					CompressTest.assertCharSequenceEquals("post pass indexed node", actual1Node, actual2.getNode());
					Assert.assertTrue(reader.hasNext());
					reader.pass();
				}
				reader.checkComplete();
				Assert.assertEquals(34, in.read());
				Assert.assertEquals(12, in.read());
				Assert.assertEquals(27, in.read());
			} finally {
				in.close();
			}
		}, "ReadTest").attach(
				new ExceptionThread(() -> {
					CompressNodeWriter writer = new CompressNodeWriter(out, nodes.size());
					try {
						for (IndexedNode node : nodes) {
							writer.appendNode(node);
						}
						writer.writeCRC();
						out.write(34);
						out.write(12);
						out.write(27);
					} finally {
						out.close();
					}
				}, "WriteTest")
		).startAll().joinAndCrashIfRequired();
	}

	@Test
	public void writeReadMergeTest() throws InterruptedException, IOException {
		PipedOutputStream node1Out = new PipedOutputStream();
		PipedInputStream node1In = new PipedInputStream();
		node1Out.connect(node1In);

		PipedOutputStream node2Out = new PipedOutputStream();
		PipedInputStream node2In = new PipedInputStream();
		node2Out.connect(node2In);

		PipedOutputStream finalOut = new PipedOutputStream();
		PipedInputStream finalIn = new PipedInputStream();
		finalOut.connect(finalIn);

		List<IndexedNode> nodes1 = Arrays.asList(
				new IndexedNode("zzzaaa", 1),
				new IndexedNode("zzzccc", 2),
				new IndexedNode("zzzddd", 6)
		);
		List<IndexedNode> nodes2 = Arrays.asList(
				new IndexedNode("zzzbbb", 3),
				new IndexedNode("zzzeee", 4),
				new IndexedNode("zzzfff", 5),
				new IndexedNode("zzzggg", 7)
		);
		List<IndexedNode> finalExcepted = Arrays.asList(
				new IndexedNode("zzzaaa", 1),
				new IndexedNode("zzzbbb", 3),
				new IndexedNode("zzzccc", 2),
				new IndexedNode("zzzddd", 6),
				new IndexedNode("zzzeee", 4),
				new IndexedNode("zzzfff", 5),
				new IndexedNode("zzzggg", 7)
		);
		new ExceptionThread(() -> {
			CompressNodeReader reader = new CompressNodeReader(finalIn);
			Assert.assertEquals(finalExcepted.size(), reader.getSize());
			try {
				for (IndexedNode excepted : finalExcepted) {
					Assert.assertTrue(reader.hasNext());
					IndexedNode actual = reader.next();
					Assert.assertEquals(excepted.getIndex(), actual.getIndex());
					CompressTest.assertCharSequenceEquals("merged node", excepted.getNode(), actual.getNode());
				}
				reader.checkComplete();
				Assert.assertEquals(98, finalIn.read());
				Assert.assertEquals(18, finalIn.read());
				Assert.assertEquals(22, finalIn.read());
			} finally {
				finalIn.close();
			}
		}, "ReadTest").attach(
				new ExceptionThread(() -> {
					try {
						CompressUtil.writeCompressedSection(nodes1, node1Out, null);
						node1Out.write(34);
						node1Out.write(12);
						node1Out.write(27);
					} finally {
						node1Out.close();
					}
				}, "Write1Test"),
				new ExceptionThread(() -> {
					try {
						CompressUtil.writeCompressedSection(nodes2, node2Out, null);
						node2Out.write(42);
						node2Out.write(19);
						node2Out.write(1);
					} finally {
						node2Out.close();
					}
				}, "Write2Test"),
				new ExceptionThread(() -> {
					try {
						CompressUtil.mergeCompressedSection(node1In, node2In, finalOut, null);
						finalOut.write(98);
						finalOut.write(18);
						finalOut.write(22);

						Assert.assertEquals(34, node1In.read());
						Assert.assertEquals(12, node1In.read());
						Assert.assertEquals(27, node1In.read());

						Assert.assertEquals(42, node2In.read());
						Assert.assertEquals(19, node2In.read());
						Assert.assertEquals(1, node2In.read());
					} finally {
						try {
							node1In.close();
						} finally {
							try {
								node2In.close();
							} finally {
								finalOut.close();
							}
						}
					}
				}, "MergeTest")
		).startAll().joinAndCrashIfRequired();
	}
}
