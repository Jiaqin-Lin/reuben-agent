import { useEffect, useMemo, useRef } from 'react';
import { marked } from 'marked';
import DOMPurify from 'dompurify';
import hljs from 'highlight.js/lib/core';
import bash from 'highlight.js/lib/languages/bash';
import java from 'highlight.js/lib/languages/java';
import javascript from 'highlight.js/lib/languages/javascript';
import json from 'highlight.js/lib/languages/json';
import sql from 'highlight.js/lib/languages/sql';
import xml from 'highlight.js/lib/languages/xml';
import yaml from 'highlight.js/lib/languages/yaml';

marked.setOptions({ breaks: true, gfm: true });

hljs.registerLanguage('bash', bash);
hljs.registerLanguage('java', java);
hljs.registerLanguage('javascript', javascript);
hljs.registerLanguage('json', json);
hljs.registerLanguage('sql', sql);
hljs.registerLanguage('xml', xml);
hljs.registerLanguage('yaml', yaml);

interface Props {
  content: string;
  className?: string;
}

export function Markdown({ content, className }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);

  const html = useMemo(() => {
    if (!content) return '';
    const rendered = marked.parse(content, { async: false }) as string;
    return DOMPurify.sanitize(rendered, { ADD_ATTR: ['target', 'rel', 'class'] });
  }, [content]);

  // 阶段：渲染后高亮代码块
  useEffect(() => {
    if (!containerRef.current) return;
    containerRef.current.querySelectorAll('pre code').forEach((block) => {
      try {
        hljs.highlightElement(block as HTMLElement);
      } catch {
        // 未知语言忽略
      }
    });
  }, [html]);

  return (
    <div
      ref={containerRef}
      className={`markdown-body ${className ?? ''}`}
      // eslint-disable-next-line react/no-danger
      dangerouslySetInnerHTML={{ __html: html }}
    />
  );
}
