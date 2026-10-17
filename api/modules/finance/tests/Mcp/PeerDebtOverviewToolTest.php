<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Finance\Mcp\Tool\ManagePeerDebtsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** `manage_peer_debts` list and summary by person and by object (MAG-455). World: `peer_debts_overview.yaml`. */
class PeerDebtOverviewToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Api/fixtures/peer_debts_overview.yaml');
        $this->loginFixtureUser();
    }

    /** @return array<string, mixed> */
    private function call(string $action, mixed ...$args): array
    {
        $tool = self::getContainer()->get(ManagePeerDebtsTool::class);

        return json_decode($tool($action, ...$args), true, 512, JSON_THROW_ON_ERROR);
    }

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    public function testTheSummaryGivesTheNetPerPerson(): void
    {
        $summary = $this->call('summary');

        self::assertSame(35000, $summary['owedToMeCents']);
        self::assertSame(28000, $summary['iOweCents']);
        self::assertSame(
            [['Julie', 15000, false], ['Tom', -8000, true]],
            array_map(static fn (array $person): array => [$person['name'], $person['netCents'], $person['compensates']], $summary['byContact']),
        );
    }

    public function testListFiltersByDirectionAndGivesTheHistory(): void
    {
        self::assertSame(['Essence du road trip'], array_column($this->call('list', direction: 'i_owe')['peerDebts'], 'object'));
        self::assertSame(['Gîte de Crozon'], array_column($this->call('list', direction: 'owed_to_me', status: 'open')['peerDebts'], 'object'));

        $gite = $this->call('list', contactId: $this->id('julie'))['peerDebts'][0];
        self::assertSame([['VIR DE JULIE ROUX', 5000, 'Julie']], array_map(
            static fn (array $entry): array => [$entry['label'], $entry['amountCents'], $entry['contactName']],
            $gite['history'],
        ));
    }

    public function testAnInvalidDirectionIsRefused(): void
    {
        self::assertArrayHasKey('error', $this->call('list', direction: 'both'));
    }
}
