import {Fragment, type ReactNode} from "react";

// A small, deliberately limited Markdown renderer. All content remains React text;
// HTML, images and links are never interpreted as executable markup or URLs.
function inline(text: string): ReactNode[] {
  return text.split(/(`[^`\n]+`|\*\*[^*\n]+\*\*|__[^_\n]+__)/g).map((part, i) =>
    part.startsWith("`") && part.endsWith("`") ? <code key={i}>{part.slice(1, -1)}</code> :
    (part.startsWith("**") && part.endsWith("**")) || (part.startsWith("__") && part.endsWith("__")) ? <strong key={i}>{part.slice(2, -2)}</strong> : <Fragment key={i}>{part}</Fragment>);
}

export default function MessageContent({text}: {text: string}) {
  const lines = text.replace(/\r\n?/g, "\n").split("\n"), blocks: ReactNode[] = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i], key = i;
    const fence = line.match(/^\s*(`{3,}|~{3,})[^`~]*$/);
    if (fence) {
      i++;
      const code: string[] = [];
      while (i < lines.length && !new RegExp(`^\\s*${fence[1][0]}{${fence[1].length},}\\s*$`).test(lines[i])) code.push(lines[i++]);
      if (i < lines.length) i++;
      blocks.push(<pre key={key}><code>{code.join("\n")}</code></pre>);
    } else if (/^\s*(?:[-+*]|\d+[.)])\s+/.test(line)) {
      const ordered = /^\s*\d+[.)]\s+/.test(line), items: ReactNode[] = [];
      const pattern = ordered ? /^\s*\d+[.)]\s+/ : /^\s*[-+*]\s+/;
      while (i < lines.length && pattern.test(lines[i])) {
        items.push(<li key={i}>{inline(lines[i++].replace(pattern, ""))}</li>);
      }
      blocks.push(ordered ? <ol key={key} start={Number(line.trim().match(/^\d+/)?.[0])}>{items}</ol> : <ul key={key}>{items}</ul>);
    } else if (/^#{1,6}\s+/.test(line)) {
      blocks.push(<p key={key}><strong>{inline(line.replace(/^#{1,6}\s+/, ""))}</strong></p>); i++;
    } else if (!line.trim()) { i++; }
    else {
      const paragraph: string[] = [lines[i++]];
      while (i < lines.length && lines[i].trim() && !/^\s*(?:`{3,}|~{3,}|#{1,6}\s|[-+*]\s|\d+[.)]\s)/.test(lines[i])) paragraph.push(lines[i++]);
      blocks.push(<p key={key}>{inline(paragraph.join("\n"))}</p>);
    }
  }
  return <div className="message-content">{blocks}</div>;
}
