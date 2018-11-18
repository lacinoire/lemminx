/**
 *  Copyright (c) 2018 Angelo ZERR.
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  Contributors:
 *  Angelo Zerr <angelo.zerr@gmail.com> - initial API and implementation
 */
package org.eclipse.lsp4xml.dom;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.lsp4xml.commons.BadLocationException;
import org.eclipse.lsp4xml.commons.TextDocument;
import org.eclipse.lsp4xml.dom.DOMDocumentType.DocumentTypeKind;
import org.eclipse.lsp4xml.dom.parser.Scanner;
import org.eclipse.lsp4xml.dom.parser.TokenType;
import org.eclipse.lsp4xml.dom.parser.XMLScanner;
import org.eclipse.lsp4xml.uriresolver.URIResolverExtensionManager;

/**
 * Tolerant XML parser.
 *
 */
public class DOMParser {

	private static final Logger LOGGER = Logger.getLogger(DOMParser.class.getName());

	private static final DOMParser INSTANCE = new DOMParser();

	public static DOMParser getInstance() {
		return INSTANCE;
	}

	private DOMParser() {

	}

	public DOMDocument parse(String text, String uri, URIResolverExtensionManager resolverExtensionManager) {
		return parse(new TextDocument(text, uri), resolverExtensionManager);
	}

	public DOMDocument parse(TextDocument document, URIResolverExtensionManager resolverExtensionManager) {

		String text = document.getText();
		Scanner scanner = XMLScanner.createScanner(text);
		DOMDocument xmlDocument = new DOMDocument(document, resolverExtensionManager);

		DOMNode curr = xmlDocument;
		DOMNode lastClosed = xmlDocument;
		DOMAttr attr = null;
		int endTagOpenOffset = -1;
		String pendingAttribute = null;
		TokenType token = scanner.scan();
		while (token != TokenType.EOS) {
			switch (token) {
			case StartTagOpen: {
				DOMElement child = xmlDocument.createElement(scanner.getTokenOffset(), text.length());
				child.startTagOpenOffset = scanner.getTokenOffset();
				curr.addChild(child);
				curr = child;
				break;
			}

			case StartTag: {
				DOMElement element = (DOMElement) curr;
				element.tag = scanner.getTokenText();
				break;
			}

			case StartTagClose:
				if (curr.isElement()) {
					DOMElement element = (DOMElement) curr;
					curr.end = scanner.getTokenEnd(); // might be later set to end tag position
					element.startTagCloseOffset = scanner.getTokenOffset();
					if (element.getTagName() != null && isEmptyElement(element.getTagName()) && curr.parent != null) {
						curr.closed = true;
						curr = curr.parent;
					}
				} else if (curr.isProcessingInstruction() || curr.isProlog()) {
					ProcessingInstruction element = (ProcessingInstruction) curr;
					curr.end = scanner.getTokenEnd(); // might be later set to end tag position
					element.startTagClose = true;
					if (element.getTarget() != null && isEmptyElement(element.getTarget()) && curr.parent != null) {
						curr.closed = true;
						curr = curr.parent;
					}
				}
				break;

			case EndTagOpen:
				endTagOpenOffset = scanner.getTokenOffset();
				break;

			case EndTag:
				// end tag (ex: </root>)
				String closeTag = scanner.getTokenText().toLowerCase();
				DOMNode current = curr;
				while (!(curr.isElement() && ((DOMElement) curr).isSameTag(closeTag)) && curr.parent != null) {
					curr.end = endTagOpenOffset;
					curr.closed = false;
					curr = curr.parent;
				}
				if (curr != xmlDocument) {
					curr.closed = true;
					if (curr.isElement()) {
						((DOMElement) curr).endTagOpenOffset = endTagOpenOffset;
					} else if (curr.isProcessingInstruction() || curr.isProlog()) {
						((ProcessingInstruction) curr).endTagOpenOffset = endTagOpenOffset;
					}
				} else {
					// element open tag not found (ex: <root>) add a fake elementg which have just
					// end tag (no start tag).
					DOMElement element = xmlDocument.createElement(scanner.getTokenOffset() - 2, text.length());
					element.endTagOpenOffset = endTagOpenOffset;
					element.tag = closeTag;
					current.addChild(element);
					curr = element;
				}
				break;

			case StartTagSelfClose:
				if (curr.parent != null) {
					curr.closed = true;
					((DOMElement) curr).selfClosed = true;
					curr.end = scanner.getTokenEnd();
					lastClosed = curr;
					curr = curr.parent;
				}
				break;

			case EndTagClose:
				if (curr.parent != null) {
					curr.end = scanner.getTokenEnd();
					lastClosed = curr;
					curr = curr.parent;
				}
				break;

			case AttributeName: {
				pendingAttribute = scanner.getTokenText();
				attr = new DOMAttr(pendingAttribute, scanner.getTokenOffset(),
						scanner.getTokenOffset() + pendingAttribute.length(), curr);
				curr.setAttributeNode(attr);
				break;
			}

			case AttributeValue: {
				String value = scanner.getTokenText();
				if (curr.hasAttributes() && attr != null) {
					attr.setValue(value, scanner.getTokenOffset(), scanner.getTokenOffset() + value.length());
				}
				pendingAttribute = null;
				attr = null;
				break;
			}

			case CDATATagOpen: {
				DOMCDATASection cdataNode = xmlDocument.createCDataSection(scanner.getTokenOffset(), text.length());
				curr.addChild(cdataNode);
				curr = cdataNode;
				break;
			}

			case CDATAContent: {
				DOMCDATASection cdataNode = (DOMCDATASection) curr;
				cdataNode.startContent = scanner.getTokenOffset();
				cdataNode.endContent = scanner.getTokenEnd();
				break;
			}

			case CDATATagClose: {
				curr.end = scanner.getTokenEnd();
				curr.closed = true;
				curr = curr.parent;
				break;
			}

			case StartPrologOrPI: {
				ProcessingInstruction prologOrPINode = xmlDocument.createProcessingInstruction(scanner.getTokenOffset(),
						text.length());
				curr.addChild(prologOrPINode);
				curr = prologOrPINode;
				break;
			}

			case PIName: {
				ProcessingInstruction processingInstruction = ((ProcessingInstruction) curr);
				processingInstruction.target = scanner.getTokenText();
				processingInstruction.processingInstruction = true;
				break;
			}

			case PrologName: {
				ProcessingInstruction processingInstruction = ((ProcessingInstruction) curr);
				processingInstruction.target = scanner.getTokenText();
				processingInstruction.prolog = true;
				break;
			}

			case PIContent: {
				ProcessingInstruction processingInstruction = (ProcessingInstruction) curr;
				processingInstruction.startContent = scanner.getTokenOffset();
				processingInstruction.endContent = scanner.getTokenEnd();
				break;
			}

			case PIEnd:
			case PrologEnd: {
				curr.end = scanner.getTokenEnd();
				curr.closed = true;
				curr = curr.parent;
				break;
			}

			case StartCommentTag: {
				DOMComment comment = xmlDocument.createComment(scanner.getTokenOffset(), text.length());
				curr.addChild(comment);
				curr = comment;
				try {
					int endLine = document.positionAt(lastClosed.end).getLine();
					int startLine = document.positionAt(curr.start).getLine();
					if (endLine == startLine && lastClosed.end <= curr.start) {
						comment.commentSameLineEndTag = true;
					}
				} catch (BadLocationException e) {
					LOGGER.log(Level.SEVERE, "XMLParser StartCommentTag bad offset in document", e);
				}
				break;
			}

			case Comment: {
				DOMComment comment = (DOMComment) curr;
				comment.startContent = scanner.getTokenOffset();
				comment.endContent = scanner.getTokenEnd();
				break;
			}

			case StartDoctypeTag: {
				DOMDocumentType doctype = xmlDocument.createDocumentType(scanner.getTokenOffset(), text.length());
				curr.addChild(doctype);
				doctype.parent = curr;
				curr = doctype;
				break;
			}

			case DoctypeName: {
				DOMDocumentType doctype = (DOMDocumentType) curr;
				doctype.setName(scanner.getTokenText());
				break;
			}

			case DocTypeKindPUBLIC: {
				DOMDocumentType doctype = (DOMDocumentType) curr;
				doctype.setKind(DocumentTypeKind.PUBLIC);
				break;
			}

			case DocTypeKindSYSTEM: {
				DOMDocumentType doctype = (DOMDocumentType) curr;
				doctype.setKind(DocumentTypeKind.SYSTEM);
				break;
			}

			case DoctypePublicId: {
				DOMDocumentType doctype = (DOMDocumentType) curr;
				doctype.setPublicId(scanner.getTokenText());
				break;
			}

			case DoctypeSystemId: {
				DOMDocumentType doctype = (DOMDocumentType) curr;
				doctype.setSystemId(scanner.getTokenText());
				break;
			}

			case InternalDTDContent: {
				DOMDocumentType doctype = (DOMDocumentType) curr;
				doctype.setInternalDTD(scanner.getTokenText());
				break;
			}

			case EndDoctypeTag: {
				curr.end = scanner.getTokenEnd();
				curr.closed = true;
				curr = curr.parent;
				break;
			}

			case EndCommentTag: {
				curr.end = scanner.getTokenEnd();
				curr.closed = true;
				curr = curr.parent;
				break;
			}

			case Content: {
				// FIXME: don't use getTokenText (substring) to know if the content is only
				// spaces or line feed (scanner should know that).
				String content = scanner.getTokenText();
				if (content.trim().length() == 0) { // if string is only whitespaces
					break;
				}
				int start = scanner.getTokenOffset();
				int end = scanner.getTokenEnd();
				DOMText textNode = xmlDocument.createText(start, end);
				textNode.closed = true;
				curr.addChild(textNode);
				break;
			}

			default:
			}
			token = scanner.scan();
		}
		while (curr.parent != null) {
			curr.end = text.length();
			curr.closed = false;
			curr = curr.parent;
		}
		return xmlDocument;
	}

	private static boolean isEmptyElement(String tag) {
		return false;
	}

}
