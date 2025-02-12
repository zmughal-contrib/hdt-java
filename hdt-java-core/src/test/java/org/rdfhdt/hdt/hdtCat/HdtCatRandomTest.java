package org.rdfhdt.hdt.hdtCat;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.rdfhdt.hdt.exceptions.NotFoundException;
import org.rdfhdt.hdt.exceptions.ParserException;
import org.rdfhdt.hdt.hdt.HDT;
import org.rdfhdt.hdt.hdt.HDTManager;
import org.rdfhdt.hdt.hdt.HDTManagerTest;
import org.rdfhdt.hdt.hdtDiff.HdtDiffTest;
import org.rdfhdt.hdt.iterator.utils.CombinedIterator;
import org.rdfhdt.hdt.options.HDTOptionsKeys;
import org.rdfhdt.hdt.options.HDTSpecification;
import org.rdfhdt.hdt.util.LargeFakeDataSetStreamSupplier;
import org.rdfhdt.hdt.util.io.AbstractMapMemoryTest;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@RunWith(Parameterized.class)
public class HdtCatRandomTest extends AbstractMapMemoryTest {
	@Parameterized.Parameters(name = "{0} unicode:{2}")
	public static Collection<Object[]> genParam() {
		List<Object[]> list = new ArrayList<>();
		for (HdtDiffTest.DictionaryTestData data : HdtDiffTest.DICTIONARY_TEST_DATA) {
			if (data.dictionaryType.equals(HDTOptionsKeys.DICTIONARY_TYPE_VALUE_FOUR_PSFC_SECTION)) {
				continue; // TODO: not handled?
			}
			list.add(new Object[]{data.dictionaryType, data.dictionaryTempType, true});
			list.add(new Object[]{data.dictionaryType, data.dictionaryTempType, false});
		}
		return list;
	}


	@Rule
	public TemporaryFolder tempDir = new TemporaryFolder();
	private final HDTSpecification spec;
	private final boolean unicode;

	public HdtCatRandomTest(String dictionaryType, String tempDictionaryImpl, boolean unicode) {
		spec = new HDTSpecification();
		spec.set(HDTOptionsKeys.DICTIONARY_TYPE_KEY, dictionaryType);
		spec.set(HDTOptionsKeys.TEMP_DICTIONARY_IMPL_KEY, tempDictionaryImpl);
		this.unicode = unicode;
	}

	@Test
	public void fakeTest() throws ParserException, IOException, NotFoundException {
		File root = tempDir.newFolder();
		String location = new File(root, "catHdt").getAbsolutePath();
		String hdt1F = new File(root, "hdt1").getAbsolutePath();
		String hdt2F = new File(root, "hdt2").getAbsolutePath();
		String hdtCatExcepted = new File(root, "hdtCatExcepted").getAbsolutePath();
		String catOutput = new File(root, "catResult").getAbsolutePath();

		long size = 10_000;
		long seed = 482;

		LargeFakeDataSetStreamSupplier supplier = LargeFakeDataSetStreamSupplier
				.createSupplierWithMaxTriples(size, seed)
				.withMaxFakeType(4)
				.withMaxElementSplit(1000)
				.withUnicode(unicode);
		supplier.createAndSaveFakeHDT(spec, hdt1F);
		supplier.createAndSaveFakeHDT(spec, hdt2F);

		supplier.reset();

		try (HDT hdtMerge = HDTManager.generateHDT(CombinedIterator.combine(
				List.of(supplier.createTripleStringStream(), supplier.createTripleStringStream())
		), "http://w", spec,  null)) {
			hdtMerge.saveToHDT(hdtCatExcepted, null);
		}

		try (HDT cat = HDTManager.catHDT(location, hdt1F, hdt2F, spec, null)) {
			cat.saveToHDT(catOutput, null);
		}

		try (HDT excepted = HDTManager.loadHDT(hdtCatExcepted, null, spec);
			 HDT actual = HDTManager.loadHDT(catOutput, null, spec)) {
			HDTManagerTest.HDTManagerTestBase.checkHDTConsistency(excepted);
			HDTManagerTest.HDTManagerTestBase.checkHDTConsistency(actual);
			HDTManagerTest.HDTManagerTestBase.assertEqualsHDT(excepted, actual);
		}

		try (HDT excepted = HDTManager.mapHDT(hdtCatExcepted, null, spec)) {
			try (HDT actual = HDTManager.mapHDT(catOutput, null, spec)) {
				HDTManagerTest.HDTManagerTestBase.checkHDTConsistency(actual);
				HDTManagerTest.HDTManagerTestBase.assertEqualsHDT(excepted, actual);
			}
		}
	}
}
