<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Mcp;

use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** manage_food_intakes and get_daily_balance are reached only with a bearer, like every MCP tool (MAG-748). */
class FoodIntakeAccessTest extends WebTestCase
{
    /** @return iterable<string, array{string, array<string, mixed>}> */
    public static function calls(): iterable
    {
        yield 'manage_food_intakes' => ['manage_food_intakes', ['action' => 'list', 'date' => '2026-11-05']];
        yield 'get_daily_balance' => ['get_daily_balance', ['date' => '2026-11-05']];
    }

    /**
     * @param array<string, mixed> $arguments
     */
    #[DataProvider('calls')]
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
