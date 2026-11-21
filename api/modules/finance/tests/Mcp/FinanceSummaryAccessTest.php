<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Mcp;

use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** get_finance_summary and get_finance_projection are reached only with a bearer, like every MCP tool. */
class FinanceSummaryAccessTest extends WebTestCase
{
    /** @return iterable<string, array{string, array<string, mixed>}> */
    public static function tools(): iterable
    {
        yield 'get_finance_summary' => ['get_finance_summary', ['period' => '2026-11']];
        yield 'get_finance_projection' => ['get_finance_projection', ['months' => 3]];
    }

    /** @param array<string, mixed> $arguments */
    #[DataProvider('tools')]
    public function testWithoutABearerTheCallIsRefusedWith401(string $tool, array $arguments): void
    {
        $client = self::createClient();

        $client->request('POST', '/_mcp', server: [
            'CONTENT_TYPE' => 'application/json',
            'HTTP_ACCEPT' => 'application/json, text/event-stream',
        ], content: json_encode([
            'jsonrpc' => '2.0',
            'id' => 1,
            'method' => 'tools/call',
            'params' => ['name' => $tool, 'arguments' => $arguments],
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }
}
