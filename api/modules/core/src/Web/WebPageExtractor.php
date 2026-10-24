<?php

declare(strict_types=1);

namespace Maggie\Core\Web;

/**
 * Turns the HTML of a page into what the model needs: its title, the readable main text
 * (bounded) and every schema.org object of its JSON-LD, as published.
 */
class WebPageExtractor
{
    public const MAX_TEXT_CHARS = 20_000;
    private const MAX_STRUCTURED_OBJECTS = 30;
    private const MAX_STRUCTURED_BYTES = 150_000;
    private const MIN_MAIN_CHARS = 200;

    private const SKIPPED = ['script', 'style', 'noscript', 'template', 'svg', 'iframe', 'canvas', 'head', 'select', 'button', 'input', 'textarea', 'object', 'embed'];
    private const CHROME = ['nav', 'footer', 'aside', 'form'];
    private const BLOCKS = [
        'p', 'div', 'section', 'article', 'main', 'header', 'li', 'ul', 'ol', 'tr', 'td', 'th', 'table', 'br', 'hr',
        'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'blockquote', 'pre', 'dt', 'dd', 'dl', 'figure', 'figcaption', 'address', 'details', 'summary',
    ];

    /** @return array{title: string, text: string, structured: list<array<string, mixed>>} */
    public function extract(string $html): array
    {
        $document = new \DOMDocument();
        $previous = libxml_use_internal_errors(true);
        $document->loadHTML('<?xml encoding="UTF-8">'.$html, LIBXML_NOERROR | LIBXML_NOWARNING | LIBXML_NONET | LIBXML_COMPACT);
        libxml_clear_errors();
        libxml_use_internal_errors($previous);

        $xpath = new \DOMXPath($document);

        return [
            'title' => $this->title($xpath),
            'text' => $this->text($document, $xpath),
            'structured' => $this->structured($xpath),
        ];
    }

    private function title(\DOMXPath $xpath): string
    {
        foreach (['//head/title', '//meta[@property="og:title"]/@content', '//h1'] as $query) {
            $node = $xpath->query($query)->item(0);
            $title = null === $node ? '' : $this->collapse($node->textContent);
            if ('' !== $title) {
                return $title;
            }
        }

        return '';
    }

    private function text(\DOMDocument $document, \DOMXPath $xpath): string
    {
        $candidates = [];
        foreach (['//main', '//*[@role="main"]'] as $query) {
            $node = $xpath->query($query)->item(0);
            if (null !== $node) {
                $candidates[] = $node;
            }
        }
        $article = null;
        $longest = 0;
        foreach ($xpath->query('//article') as $node) {
            if (($length = mb_strlen($node->textContent)) > $longest) {
                [$article, $longest] = [$node, $length];
            }
        }
        if (null !== $article) {
            $candidates[] = $article;
        }
        if (null !== $document->getElementsByTagName('body')->item(0)) {
            $candidates[] = $document->getElementsByTagName('body')->item(0);
        }

        $text = '';
        foreach ($candidates as $candidate) {
            $text = $this->readable($candidate);
            if (mb_strlen($text) >= self::MIN_MAIN_CHARS) {
                break;
            }
        }

        if (mb_strlen($text) > self::MAX_TEXT_CHARS) {
            $text = rtrim(mb_substr($text, 0, self::MAX_TEXT_CHARS)).' […]';
        }

        return $text;
    }

    private function readable(\DOMNode $root): string
    {
        $buffer = '';
        $this->walk($root, $buffer);
        $lines = array_filter(array_map($this->collapse(...), explode("\n", $buffer)), static fn (string $line): bool => '' !== $line);

        return implode("\n", $lines);
    }

    private function walk(\DOMNode $node, string &$buffer): void
    {
        foreach ($node->childNodes as $child) {
            if ($child instanceof \DOMText) {
                $buffer .= $child->textContent;

                continue;
            }
            if (!$child instanceof \DOMElement) {
                continue;
            }
            $name = strtolower($child->tagName);
            if (\in_array($name, self::SKIPPED, true) || \in_array($name, self::CHROME, true)) {
                continue;
            }
            $block = \in_array($name, self::BLOCKS, true);
            if ($block) {
                $buffer .= "\n";
            }
            $this->walk($child, $buffer);
            if ($block) {
                $buffer .= "\n";
            }
        }
    }

    /** @return list<array<string, mixed>> */
    private function structured(\DOMXPath $xpath): array
    {
        $objects = [];
        $bytes = 0;
        $query = '//script[translate(@type, "ABCDEFGHIJKLMNOPQRSTUVWXYZ", "abcdefghijklmnopqrstuvwxyz")="application/ld+json"]';

        foreach ($xpath->query($query) as $script) {
            $json = trim(preg_replace('/^\s*(<!--|\/\/\s*<!\[CDATA\[)|(-->|\/\/\s*\]\]>)\s*$/', '', trim($script->textContent)));
            try {
                $decoded = json_decode($json, true, 64, JSON_THROW_ON_ERROR);
            } catch (\JsonException) {
                continue;
            }
            if (!\is_array($decoded)) {
                continue;
            }

            foreach ($this->flatten($decoded) as $object) {
                $size = \strlen(json_encode($object, JSON_UNESCAPED_UNICODE) ?: '');
                if (\count($objects) >= self::MAX_STRUCTURED_OBJECTS || $bytes + $size > self::MAX_STRUCTURED_BYTES) {
                    break 2;
                }
                $objects[] = $object;
                $bytes += $size;
            }
        }

        return $objects;
    }

    /**
     * A JSON-LD block is an object, a list of objects, or an object whose `@graph` lists them.
     *
     * @param array<mixed> $node
     *
     * @return list<array<string, mixed>>
     */
    private function flatten(array $node): array
    {
        if (array_is_list($node)) {
            return array_merge([], ...array_map(fn ($item) => \is_array($item) ? $this->flatten($item) : [], $node));
        }

        $objects = isset($node['@type']) ? [$node] : [];
        if (isset($node['@graph']) && \is_array($node['@graph'])) {
            $objects = array_merge($objects, $this->flatten($node['@graph']));
        }

        return $objects;
    }

    private function collapse(string $text): string
    {
        return trim(preg_replace('/[ \t\x{00A0}\r\f\v]+/u', ' ', $text) ?? '');
    }
}
