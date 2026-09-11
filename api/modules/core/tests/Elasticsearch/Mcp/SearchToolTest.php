<?php

namespace Maggie\Core\Tests\Elasticsearch\Mcp;

use Maggie\Core\Elasticsearch\Mcp\SearchTool;
use Maggie\Core\Elasticsearch\SearchService;
use Maggie\Core\Entity\User;
use Maggie\Core\Mcp\McpUserContext;
use PHPUnit\Framework\TestCase;

class SearchToolTest extends TestCase
{
    private SearchService $searchService;
    private McpUserContext $userContext;
    private User $user;

    protected function setUp(): void
    {
        $this->user = new User();
        $this->user->setEmail('test@example.com');
        $this->user->setGoogleId('google-test-id');
        $this->user->setName('Test User');

        $this->userContext = $this->createMock(McpUserContext::class);
        $this->userContext->method('getUser')->willReturn($this->user);

        $this->searchService = $this->createMock(SearchService::class);
    }

    public function testSearchReturnsResults(): void
    {
        $this->searchService->method('search')->willReturn([
            'total' => 2,
            'results' => [
                ['index' => 'events', 'id' => 'abc123', 'score' => 1.5, 'data' => ['summary' => 'Meeting'], 'highlights' => ['summary' => ['<em>Meeting</em>']]],
                ['index' => 'tasks', 'id' => 'def456', 'score' => 1.2, 'data' => ['title' => 'Meeting prep'], 'highlights' => ['title' => ['<em>Meeting</em> prep']]],
            ],
        ]);

        $tool = new SearchTool($this->searchService, $this->userContext);
        $result = json_decode($tool('meeting'), true);

        self::assertSame(2, $result['total']);
        self::assertCount(2, $result['results']);
        self::assertSame('events', $result['results'][0]['index']);
        self::assertSame('tasks', $result['results'][1]['index']);
    }

    public function testSearchWithTypesFilter(): void
    {
        $this->searchService->expects($this->once())
            ->method('search')
            ->with('test', (string) $this->user->getId(), ['events', 'tasks'], 0, 5)
            ->willReturn(['total' => 0, 'results' => []]);

        $tool = new SearchTool($this->searchService, $this->userContext);
        $result = json_decode($tool('test', 'events, tasks', 5), true);

        self::assertSame(0, $result['total']);
    }

    public function testSearchWithNoUserReturnsError(): void
    {
        $userContext = $this->createMock(McpUserContext::class);
        $userContext->method('getUser')->willReturn(null);

        $tool = new SearchTool($this->searchService, $userContext);
        $result = json_decode($tool('test'), true);

        self::assertArrayHasKey('error', $result);
    }

    public function testSearchErrorReturnsGracefully(): void
    {
        $this->searchService->method('search')
            ->willThrowException(new \RuntimeException('ES connection refused'));

        $tool = new SearchTool($this->searchService, $this->userContext);
        $result = json_decode($tool('test'), true);

        self::assertArrayHasKey('error', $result);
        self::assertSame(0, $result['total']);
    }
}
