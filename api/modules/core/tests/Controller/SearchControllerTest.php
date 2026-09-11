<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Controller;

use Maggie\Core\Controller\SearchController;
use Maggie\Core\Elasticsearch\SearchService;
use Maggie\Core\Entity\User;
use PHPUnit\Framework\TestCase;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\Request;

class SearchControllerTest extends TestCase
{
    private SearchService $searchService;
    private Security $security;
    private User $user;

    protected function setUp(): void
    {
        $this->user = new User();
        $this->user->setEmail('test@example.com');
        $this->user->setGoogleId('google-test-id');
        $this->user->setName('Test User');

        $this->security = $this->createMock(Security::class);
        $this->security->method('getUser')->willReturn($this->user);

        $this->searchService = $this->createMock(SearchService::class);
    }

    public function testSearchReturnsResults(): void
    {
        $this->searchService->method('search')->willReturn([
            'total' => 2,
            'results' => [
                ['index' => 'events', 'id' => 'abc123', 'score' => 1.5, 'data' => ['summary' => 'Meeting'], 'highlights' => ['summary' => ['<em>Meeting</em>']]],
                ['index' => 'tasks', 'id' => 'def456', 'score' => 1.2, 'data' => ['title' => 'Task'], 'highlights' => []],
            ],
        ]);

        $controller = new SearchController($this->searchService, $this->security);
        $request = Request::create('/api/search', 'GET', ['q' => 'meeting']);
        $response = $controller($request);
        $data = json_decode($response->getContent(), true);

        self::assertSame(200, $response->getStatusCode());
        self::assertSame(2, $data['total']);
        self::assertSame(1, $data['page']);
        self::assertSame(10, $data['limit']);
        self::assertCount(2, $data['results']);
        self::assertSame('events', $data['results'][0]['index']);
    }

    public function testMissingQueryReturns400(): void
    {
        $controller = new SearchController($this->searchService, $this->security);
        $request = Request::create('/api/search', 'GET');
        $response = $controller($request);

        self::assertSame(400, $response->getStatusCode());
        $data = json_decode($response->getContent(), true);
        self::assertArrayHasKey('error', $data);
    }

    public function testUnauthenticatedReturns401(): void
    {
        $security = $this->createMock(Security::class);
        $security->method('getUser')->willReturn(null);

        $controller = new SearchController($this->searchService, $security);
        $request = Request::create('/api/search', 'GET', ['q' => 'test']);
        $response = $controller($request);

        self::assertSame(401, $response->getStatusCode());
    }

    public function testTypesFilterIsForwarded(): void
    {
        $this->searchService->expects($this->once())
            ->method('search')
            ->with('pasta', (string) $this->user->getId(), ['recipes', 'tasks'], 0, 10)
            ->willReturn(['total' => 0, 'results' => []]);

        $controller = new SearchController($this->searchService, $this->security);
        $request = Request::create('/api/search', 'GET', ['q' => 'pasta', 'types' => 'recipes,tasks']);
        $controller($request);
    }

    public function testPaginationParameters(): void
    {
        $this->searchService->expects($this->once())
            ->method('search')
            ->with('test', (string) $this->user->getId(), null, 20, 5)
            ->willReturn(['total' => 50, 'results' => []]);

        $controller = new SearchController($this->searchService, $this->security);
        $request = Request::create('/api/search', 'GET', ['q' => 'test', 'page' => '5', 'limit' => '5']);
        $response = $controller($request);
        $data = json_decode($response->getContent(), true);

        self::assertSame(5, $data['page']);
        self::assertSame(5, $data['limit']);
    }

    public function testLimitIsCappedAt100(): void
    {
        $this->searchService->expects($this->once())
            ->method('search')
            ->with('test', (string) $this->user->getId(), null, 0, 100)
            ->willReturn(['total' => 0, 'results' => []]);

        $controller = new SearchController($this->searchService, $this->security);
        $request = Request::create('/api/search', 'GET', ['q' => 'test', 'limit' => '500']);
        $controller($request);
    }
}
