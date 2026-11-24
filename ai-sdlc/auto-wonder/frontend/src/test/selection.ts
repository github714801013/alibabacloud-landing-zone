import { act } from '@testing-library/react';

/** jsdom 的 Range 没有有效矩形，胶囊会按隐藏选区处理；补一个视口内的矩形。 */
export function stubRangeRect(rect: Partial<DOMRect> = {}) {
  const value = {
    x: 20, y: 10, top: 10, left: 20, right: 120, bottom: 30, width: 100, height: 20,
    toJSON: () => ({}), ...rect,
  } as DOMRect;
  Object.defineProperty(Range.prototype, 'getBoundingClientRect', { configurable: true, value: () => value });
}

/** 选中元素内第一个文本节点的全部内容，并触发 useTextQuote 监听的 selectionchange。 */
export function selectText(element: Element) {
  const node = document.createTreeWalker(element, NodeFilter.SHOW_TEXT).nextNode();
  if (!node?.textContent) {
    throw new Error('element has no text to select');
  }
  const range = document.createRange();
  range.setStart(node, 0);
  range.setEnd(node, node.textContent.length);
  const selection = window.getSelection();
  if (!selection) {
    throw new Error('window.getSelection is unavailable');
  }
  selection.removeAllRanges();
  selection.addRange(range);
  act(() => { document.dispatchEvent(new Event('selectionchange')); });
}

/** 选中两个元素之间的全部文本，用于跨消息选区的负向用例。 */
export function selectAcross(from: Element, to: Element) {
  const fromNode = document.createTreeWalker(from, NodeFilter.SHOW_TEXT).nextNode();
  const toNode = document.createTreeWalker(to, NodeFilter.SHOW_TEXT).nextNode();
  if (!fromNode?.textContent || !toNode?.textContent) {
    throw new Error('element has no text to select');
  }
  const range = document.createRange();
  range.setStart(fromNode, 0);
  range.setEnd(toNode, toNode.textContent.length);
  const selection = window.getSelection();
  if (!selection) {
    throw new Error('window.getSelection is unavailable');
  }
  selection.removeAllRanges();
  selection.addRange(range);
  act(() => { document.dispatchEvent(new Event('selectionchange')); });
}
