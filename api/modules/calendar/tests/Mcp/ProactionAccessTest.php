<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Mcp;

use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** The proaction tools are reached only with a bearer, like every MCP tool (MAG-974). */
class ProactionAccessTest extends WebTestCase
{
    /** @return iterable<string, array{string, array<string, mixed>}> */
    public static function tools(): iterable
    {
        yield 'schedule_proaction' => ['schedule_proaction', ['prompt' => 'Fais le point', 'scheduled_at' => '2037-01-16T18:00:00+01:00', 'rrule' => 'FREQ=WEEKLY;BYDAY=FR']];
        yield 'list_proactions' => ['list_proactions', []];
        yield 'cancel_proaction' => ['cancel_proaction', ['id' => '01J0000000000000000000000A']];
        yield 'claim_due_proactions' => ['claim_due_proactions', []];
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
