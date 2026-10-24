<?php

declare(strict_types=1);

namespace Maggie\Core\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Core\Web\WebPageException;
use Maggie\Core\Web\WebPageExtractor;
use Maggie\Core\Web\WebPageFetcher;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(
    name: 'read_web_page',
    description: "Read a public web page: returns its `title`, its main readable `text` (bounded) and `structured`, the schema.org JSON-LD objects of the page as published (`Recipe` with its ingredients and steps, `Event`, `Product`, `Article`, `LocalBusiness`…). Use it when the screen context gives a page address or the user cites a link, BEFORE acting on that page (creating a recipe, an event, a grocery item…): a screenshot or a title is never the content. Only public http(s) pages are read, without JavaScript and without the user's login; a page that is private, too big, too slow or refused comes back as an `error` with the reason — say so and ask the user for the content instead. Reading changes nothing.",
)]
class ReadWebPageTool
{
    public function __construct(
        private readonly McpUserContext $userContext,
        private readonly WebPageFetcher $fetcher,
        private readonly WebPageExtractor $extractor,
    ) {
    }

    public function __invoke(string $url): string
    {
        try {
            $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return $this->encode(['error' => $e->getMessage()]);
        }

        try {
            $page = $this->fetcher->fetch($url);
        } catch (WebPageException $e) {
            return $this->encode(['error' => $e->getMessage()]);
        }
        $content = $this->extractor->extract($page->html);

        return $this->encode([
            'url' => trim($url),
            'finalUrl' => $page->finalUrl,
            'title' => $content['title'],
            'text' => $content['text'],
            'structured' => $content['structured'],
            'fetchedAt' => (new \DateTimeImmutable())->format(\DATE_ATOM),
        ]);
    }

    /** @param array<string, mixed> $data */
    private function encode(array $data): string
    {
        return json_encode($data, JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE);
    }
}
