<?php

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Mcp\Tool\GetDayContextTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** get_day_context (MAG-811): the contract's shape, errors, and the user's own periods only. */
class GetDayContextToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Api/fixtures/PeriodApiTest.yaml');
    }

    /** @return array<string, mixed> */
    private function call(?string $date): array
    {
        $tool = self::getContainer()->get(GetDayContextTool::class);
        \assert($tool instanceof GetDayContextTool);

        return json_decode($tool($date), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testAnswersTheContractShape(): void
    {
        $this->loginFixtureUser();

        $answer = $this->call('2037-04-12');

        self::assertSame('2037-04-12', $answer['date']);
        self::assertTrue($answer['weekend']);
        self::assertCount(1, $answer['periods']);
        $period = $answer['periods'][0];
        self::assertSame('Vacances de printemps', $period['name']);
        self::assertSame('Perso', $period['agenda']['name']);
        self::assertArrayHasKey('id', $period['agenda']);
        self::assertTrue($period['allDay']);
        self::assertSame('2037-04-11', $period['start']);
        self::assertSame('2037-04-19', $period['end']);

        $timed = $this->call('2037-03-13')['periods'][0];
        self::assertFalse($timed['allDay']);
        self::assertSame('2037-03-13T18:00:00+01:00', $timed['end']);
    }

    public function testAnInvalidDateIsAnError(): void
    {
        $this->loginFixtureUser();

        self::assertArrayHasKey('error', $this->call('demain'));
        self::assertArrayHasKey('error', $this->call('2037-02-30'));
    }

    public function testAnotherUsersPeriodsNeverShow(): void
    {
        $this->loginFixtureUser();

        $names = array_column($this->call('2037-03-11')['periods'], 'name');

        self::assertNotContains('Période secrète', $names);
        self::assertSame(['Garde de Léo'], $names);
    }

    public function testMissingUserAnswersAnError(): void
    {
        self::assertArrayHasKey('error', $this->call('2037-03-11'));
    }
}
