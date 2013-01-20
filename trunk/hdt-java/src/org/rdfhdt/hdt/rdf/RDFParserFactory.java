/**
 * File: $HeadURL$
 * Revision: $Rev$
 * Last modified: $Date$
 * Last modified by: $Author$
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 *
 * Contacting the authors:
 *   Mario Arias:               mario.arias@deri.org
 *   Javier D. Fernandez:       jfergar@infor.uva.es
 *   Miguel A. Martinez-Prieto: migumar2@infor.uva.es
 *   Alejandro Andres:          fuzzy.alej@gmail.com
 */

package org.rdfhdt.hdt.rdf;

import org.rdfhdt.hdt.enums.RDFNotation;
import org.rdfhdt.hdt.exceptions.NotImplementedException;
import org.rdfhdt.hdt.rdf.parsers.RDFParserRIOT;
import org.rdfhdt.hdt.rdf.parsers.RDFParserSimple;

/**
 * @author mario.arias
 *
 */
public class RDFParserFactory {
	public static RDFParserCallback getParserCallback(RDFNotation notation) {
		// NOTE: Very fast but does not validate input. Might not be fully NTriples spec compliant.
		if(notation == RDFNotation.NTRIPLES) {
			return new RDFParserSimple();
		}
		
		if(notation == RDFNotation.NTRIPLES || notation == RDFNotation.TURTLE || notation == RDFNotation.RDFXML) {
			return new RDFParserRIOT();
		}
		
		throw new NotImplementedException("Parser not found for notation: "+notation);
	}
}
