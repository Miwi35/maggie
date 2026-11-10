<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Mcp;

use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** get_exercises is reached only with a bearer, like every MCP tool (MAG-774). */
class GetExercisesAccessTest extends WebTestCase
{
    public function testWithoutABearerTheCallIsRefusedWith401(): void
    {
        $client = self::createClient();

        $client->request('POST', '/_mcp', server: [
            'CONTENT_TYPE' => 'application/json',
            'HTTP_ACCEPT' => 'application/json, text/event-stream',
        ], content: json_encode([
            'jsonrpc' => '2.0',
            'id' => 1,
            'method' => 'tools/call',
            'params' => ['name' => 'get_exercises', 'arguments' => (object) []],
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }
}
