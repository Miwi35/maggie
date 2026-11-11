<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Mcp;

use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** The training tools are reached only with a bearer, like every MCP tool (MAG-776). */
class TrainingToolsAccessTest extends WebTestCase
{
    /** @return iterable<string, array{string}> */
    public static function tools(): iterable
    {
        yield 'get_next_prescription' => ['get_next_prescription'];
        yield 'get_training_status' => ['get_training_status'];
    }

    #[DataProvider('tools')]
    public function testWithoutABearerTheCallIsRefusedWith401(string $tool): void
    {
        $client = self::createClient();

        $client->request('POST', '/_mcp', server: [
            'CONTENT_TYPE' => 'application/json',
            'HTTP_ACCEPT' => 'application/json, text/event-stream',
        ], content: json_encode([
            'jsonrpc' => '2.0',
            'id' => 1,
            'method' => 'tools/call',
            'params' => ['name' => $tool, 'arguments' => (object) []],
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }
}
