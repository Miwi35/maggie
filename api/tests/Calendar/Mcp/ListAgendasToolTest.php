<?php

namespace App\Tests\Calendar\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Calendar\Mcp\Tool\ListAgendasTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class ListAgendasToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    protected function setUp(): void
    {
        self::bootKernel();
    }

    private function getTool(): ListAgendasTool
    {
        return self::getContainer()->get(ListAgendasTool::class);
    }

    public function testListAgendasReturnsEmpty(): void
    {
        $this->purgeDatabase();

        $tool = $this->getTool();
        $result = $tool();

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);

        self::assertSame(0, $data['count']);
        self::assertSame([], $data['agendas']);
    }

    public function testListAgendasReturnsAllAgendas(): void
    {
        $this->loadFixtures('ListAgendasToolTest.yaml');

        $tool = $this->getTool();
        $result = $tool();

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);

        self::assertSame(2, $data['count']);

        $names = array_column($data['agendas'], 'name');
        sort($names);
        self::assertSame(['Concerts', 'Personal'], $names);

        // Check structure
        $agenda = $data['agendas'][0];
        self::assertArrayHasKey('id', $agenda);
        self::assertArrayHasKey('name', $agenda);
        self::assertArrayHasKey('color', $agenda);
        self::assertArrayHasKey('isDefault', $agenda);
    }
}
