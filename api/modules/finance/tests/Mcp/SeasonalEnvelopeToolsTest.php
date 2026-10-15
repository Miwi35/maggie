<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Mcp\Tool\ManageEnvelopesTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * Seasonal amounts through manage_envelopes (MAG-415): Cinéma 20 € a month,
 * 45 € from November to February.
 */
class SeasonalEnvelopeToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    private const array WINTER = ['fromMonth' => 11, 'toMonth' => 2, 'amountCents' => 4500];

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures(__DIR__.'/../Api/fixtures/seasonal_envelope.yaml');
    }

    /** @return array<string, mixed> */
    private function envelopes(string $action, mixed ...$arguments): array
    {
        $tool = self::getContainer()->get(ManageEnvelopesTool::class);

        return json_decode($tool($action, ...$arguments), true, 512, JSON_THROW_ON_ERROR);
    }

    private function find(string $id): Envelope
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->find(Envelope::class, $id);
    }

    private function cinemaFor(int $year, int $month): ?Envelope
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Envelope::class)->findOneBy([
            'category' => $this->getFixture('cinema')->getId(),
            'year' => $year,
            'month' => $month,
        ]);
    }

    public function testSettingSeasonsNobodyIsBoundToIsRefused(): void
    {
        $data = $this->envelopes('set', categoryId: (string) $this->getFixture('cinema')->getId(), amountCents: 2000, year: 2026, month: 12, seasonalAmounts: [self::WINTER]);

        self::assertSame(MissingMcpUserException::MESSAGE, $data['error']);
        $this->assertMercureUpdateCount(0);
    }

    public function testSetCreatesASeasonalMonthlyEnvelope(): void
    {
        $this->loginFixtureUser();

        $data = $this->envelopes('set', categoryId: (string) $this->getFixture('cinema')->getId(), amountCents: 2000, year: 2026, month: 12, seasonalAmounts: [self::WINTER]);

        self::assertTrue($data['success']);
        self::assertSame(2000, $data['envelope']['amountCents']);
        self::assertSame([self::WINTER], $data['envelope']['seasonalAmounts']);
        self::assertSame(4500, $data['envelope']['monthAmountCents']);

        $created = $this->find($data['envelope']['id']);
        self::assertSame([self::WINTER], $created->getSeasonalAmounts());
        self::assertSame(4500, $created->getMonthAmountCents());

        $this->assertMercureUpdatePublished('/envelopes/');
        $this->assertElasticsearchIndexDispatched(Envelope::class);
    }

    public function testUpdateReplacesTheSeasons(): void
    {
        $this->loginFixtureUser();
        $id = (string) $this->getFixture('cinema_october')->getId();

        $data = $this->envelopes('update', envelopeId: $id, seasonalAmounts: [['fromMonth' => 10, 'toMonth' => 10, 'amountCents' => 3000]]);

        self::assertTrue($data['success']);
        self::assertSame(3000, $data['envelope']['monthAmountCents']);
        self::assertSame(2000, $this->find($id)->getAmountCents());
        self::assertSame(3000, $this->find($id)->getMonthAmountCents());
        $this->assertMercureUpdatePublished('/envelopes/');
        $this->assertElasticsearchIndexDispatched(Envelope::class);
    }

    public function testOverlappingSeasonsAreRefused(): void
    {
        $this->loginFixtureUser();
        $id = (string) $this->getFixture('cinema_october')->getId();

        $data = $this->envelopes('update', envelopeId: $id, seasonalAmounts: [
            self::WINTER,
            ['fromMonth' => 12, 'toMonth' => 3, 'amountCents' => 3000],
        ]);

        self::assertStringContainsString('overlap', $data['error']);
        self::assertSame([self::WINTER], $this->find($id)->getSeasonalAmounts());
        $this->assertMercureUpdateCount(0);
    }

    public function testAnAnnualEnvelopeHasNoSeasons(): void
    {
        $this->loginFixtureUser();

        $data = $this->envelopes('set', categoryId: (string) $this->getFixture('travel')->getId(), mode: 'annual', amountCents: 150000, year: 2027, seasonalAmounts: [self::WINTER]);

        self::assertSame('Only a monthly envelope has seasonal amounts.', $data['error']);
        $this->assertMercureUpdateCount(0);
    }

    public function testRolloverIntoTheSeasonBudgetsItsAmount(): void
    {
        $this->loginFixtureUser();

        $data = $this->envelopes('rollover', fromYear: 2026, fromMonth: 10, year: 2026, month: 11);

        self::assertSame(1, $data['created']);
        self::assertSame(4500, $data['envelopes'][0]['monthAmountCents']);

        $november = $this->cinemaFor(2026, 11);
        self::assertNotNull($november);
        self::assertSame(4500, $november->getMonthAmountCents());
        self::assertSame(2000, $november->getAmountCents());
        self::assertSame([self::WINTER], $november->getSeasonalAmounts());

        $this->assertMercureUpdatePublished('/envelopes/');
        $this->assertElasticsearchIndexDispatched(Envelope::class);
    }

    public function testRolloverOutOfTheSeasonFallsBackToTheBaseAmount(): void
    {
        $this->loginFixtureUser();

        $this->envelopes('rollover', fromYear: 2027, fromMonth: 2, year: 2027, month: 3);

        $march = $this->cinemaFor(2027, 3);
        self::assertNotNull($march);
        self::assertSame(2000, $march->getMonthAmountCents());
        self::assertSame([self::WINTER], $march->getSeasonalAmounts());
    }

    public function testStatusBudgetsTheMonthAtItsSeason(): void
    {
        $this->loginFixtureUser();

        $data = $this->envelopes('status', year: 2027, month: 2);

        self::assertCount(1, $data['budgets']);
        self::assertSame(4500, $data['budgets'][0]['amountCents']);
        self::assertSame(self::WINTER, $data['budgets'][0]['season']);
        self::assertSame(3500, $data['budgets'][0]['remainingCents']);
    }
}
