<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Mcp\Tool\ManageEnvelopesTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class EnvelopeToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    public function testSetCreatesEnvelopeForPeriod(): void
    {
        $this->loadFixtures('envelope.yaml');
        $this->loginFixtureUser();
        $category = $this->getFixture('leisure');

        $tool = self::getContainer()->get(ManageEnvelopesTool::class);
        $result = $tool('set', categoryId: (string) $category->getId(), amountCents: 15000, mode: 'monthly', year: 2026, month: 8);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame(15000, $data['envelope']['amountCents']);
        self::assertSame('monthly', $data['envelope']['mode']);
        self::assertSame(8, $data['envelope']['month']);
        self::assertSame((string) $category->getId(), $data['envelope']['categoryId']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertCount(3, $em->getRepository(Envelope::class)->findAll());

        $this->assertMercureUpdatePublished('/envelopes/');
        $this->assertElasticsearchIndexDispatched(Envelope::class);
    }

    public function testSetUpdatesTheEnvelopeAlreadyBudgetingThatPeriod(): void
    {
        $this->loadFixtures('envelope.yaml');
        $this->loginFixtureUser();
        $category = $this->getFixture('food');
        $existing = $this->getFixture('food_july');

        $tool = self::getContainer()->get(ManageEnvelopesTool::class);
        $result = $tool('set', categoryId: (string) $category->getId(), amountCents: 52000, mode: 'monthly', year: 2026, month: 7);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame((string) $existing->getId(), $data['envelope']['id']);
        self::assertSame(52000, $data['envelope']['amountCents']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertCount(2, $em->getRepository(Envelope::class)->findAll());
        self::assertSame(52000, $em->find(Envelope::class, $existing->getId())->getAmountCents());

        $this->assertMercureUpdatePublished('/envelopes/');
        $this->assertElasticsearchIndexDispatched(Envelope::class);
    }

    public function testSetRequiresCategoryAndAmount(): void
    {
        $this->loadFixtures('envelope.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(ManageEnvelopesTool::class);
        $result = $tool('set', amountCents: 15000);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertArrayHasKey('error', $data);
    }

    public function testListEnvelopesReturnsAll(): void
    {
        $this->loadFixtures('envelope.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(ManageEnvelopesTool::class);
        $result = $tool('list');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertCount(2, $data['envelopes']);
    }

    public function testStatusReportsSpentAndRemainingPerEnvelope(): void
    {
        $this->loadFixtures('envelope.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(ManageEnvelopesTool::class);
        $result = $tool('status', year: 2026, month: 7);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertSame(2026, $data['year']);
        self::assertSame(7, $data['month']);
        self::assertCount(1, $data['budgets']);

        $byCategory = array_column($data['budgets'], null, 'categoryName');

        // The monthly food envelope only counts the July debit: the credit does not.
        self::assertSame(40000, $byCategory['Alimentation']['amountCents']);
        self::assertSame(4599, $byCategory['Alimentation']['spentCents']);
        self::assertSame(35401, $byCategory['Alimentation']['remainingCents']);
        self::assertFalse($byCategory['Alimentation']['isOverspent']);

        // The annual leisure envelope counts the whole year, March debit
        // included, apart from the month's budget and totals.
        $annual = array_column($data['outsideMonthlyBudget']['annual'], null, 'categoryName');
        self::assertSame(120000, $annual['Loisirs']['amountCents']);
        self::assertSame(1200, $annual['Loisirs']['spentCents']);
        self::assertSame(118800, $annual['Loisirs']['remainingCents']);

        self::assertSame(40000, $data['totalBudgetedCents']);
        self::assertSame(4599, $data['totalSpentCents']);
        self::assertSame(35401, $data['totalRemainingCents']);
    }

    public function testStatusFlagsAnOverspentEnvelope(): void
    {
        $this->loadFixtures('envelope.yaml');
        $this->loginFixtureUser();
        $envelope = $this->getFixture('food_july');

        $tool = self::getContainer()->get(ManageEnvelopesTool::class);
        $tool('update', envelopeId: (string) $envelope->getId(), amountCents: 1000);

        $data = json_decode($tool('status', year: 2026, month: 7), true, 512, JSON_THROW_ON_ERROR);
        $byCategory = array_column($data['budgets'], null, 'categoryName');

        self::assertTrue($byCategory['Alimentation']['isOverspent']);
        self::assertSame(-3599, $byCategory['Alimentation']['remainingCents']);
    }

    public function testUpdateEnvelopeUpdatesAndPublishes(): void
    {
        $this->loadFixtures('envelope.yaml');
        $this->loginFixtureUser();
        $envelope = $this->getFixture('food_july');

        $tool = self::getContainer()->get(ManageEnvelopesTool::class);
        $result = $tool('update', envelopeId: (string) $envelope->getId(), amountCents: 30000, month: 9);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame(30000, $data['envelope']['amountCents']);
        self::assertSame(9, $data['envelope']['month']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $refreshed = $em->find(Envelope::class, $envelope->getId());
        self::assertSame(30000, $refreshed->getAmountCents());
        self::assertSame(9, $refreshed->getMonth());

        $this->assertMercureUpdatePublished('/envelopes/');
        $this->assertElasticsearchIndexDispatched(Envelope::class);
    }

    public function testSwitchingToAnnualClearsTheMonth(): void
    {
        $this->loadFixtures('envelope.yaml');
        $this->loginFixtureUser();
        $envelope = $this->getFixture('food_july');

        $tool = self::getContainer()->get(ManageEnvelopesTool::class);
        $result = $tool('update', envelopeId: (string) $envelope->getId(), mode: 'annual');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame('annual', $data['envelope']['mode']);
        self::assertNull($data['envelope']['month']);
    }

    public function testDeleteEnvelopeRemovesPublishesAndDeletes(): void
    {
        $this->loadFixtures('envelope.yaml');
        $this->loginFixtureUser();
        $envelope = $this->getFixture('food_july');

        $tool = self::getContainer()->get(ManageEnvelopesTool::class);
        $result = $tool('delete', envelopeId: (string) $envelope->getId());

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->find(Envelope::class, $envelope->getId()));

        $this->assertMercureUpdatePublished('/envelopes/');
        $this->assertElasticsearchDeleteDispatched('envelopes');
    }

    public function testUnknownActionIsReported(): void
    {
        $this->loadFixtures('envelope.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(ManageEnvelopesTool::class);
        $data = json_decode($tool('archive'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }

    /** @return array<string, mixed> */
    private function splitWith(string $motherFixture, array $children): array
    {
        $tool = self::getContainer()->get(ManageEnvelopesTool::class);

        return json_decode(
            $tool('split', envelopeId: (string) $this->getFixture($motherFixture)->getId(), children: $children),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );
    }

    private function amountOf(string $fixture): int
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->find(Envelope::class, $this->getFixture($fixture)->getId())->getAmountCents();
    }

    public function testSplitMovesTheBoundaryBetweenTwoDaughtersAndLeavesTheRestFree(): void
    {
        $this->loadFixtures('envelope_split.yaml');
        $this->loginFixtureUser();

        $data = $this->splitWith('leisure_october', [
            ['envelopeId' => (string) $this->getFixture('bars_october')->getId(), 'amountCents' => 12000],
            ['envelopeId' => (string) $this->getFixture('cinema_october')->getId(), 'amountCents' => 3000],
        ]);

        self::assertTrue($data['success']);
        self::assertSame(15000, $data['allocatedToChildrenCents']);
        self::assertSame(15000, $data['freeCents']);
        self::assertSame(12000, $this->amountOf('bars_october'));
        self::assertSame(3000, $this->amountOf('cinema_october'));
        self::assertSame(30000, $this->amountOf('leisure_october'));

        $this->assertMercurePublishedOnce('/envelopes/'.$this->getFixture('bars_october')->getId());
        $this->assertMercurePublishedOnce('/envelopes/'.$this->getFixture('cinema_october')->getId());
        $this->assertElasticsearchIndexDispatched(Envelope::class);
    }

    public function testSplitBeyondTheMotherIsRefusedAndChangesNothing(): void
    {
        $this->loadFixtures('envelope_split.yaml');
        $this->loginFixtureUser();

        $data = $this->splitWith('leisure_october', [
            ['envelopeId' => (string) $this->getFixture('bars_october')->getId(), 'amountCents' => 25000],
            ['envelopeId' => (string) $this->getFixture('cinema_october')->getId(), 'amountCents' => 5001],
        ]);

        self::assertArrayHasKey('error', $data);
        self::assertArrayNotHasKey('success', $data);
        self::assertSame(15000, $data['allocatedToChildrenCents']);
        self::assertSame(10000, $this->amountOf('bars_october'));
        self::assertSame(5000, $this->amountOf('cinema_october'));
        $this->assertMercureUpdateCount(0);
    }

    public function testSplitWithoutAUserIsRefused(): void
    {
        $this->loadFixtures('envelope_split.yaml');

        $data = $this->splitWith('leisure_october', [
            ['envelopeId' => (string) $this->getFixture('bars_october')->getId(), 'amountCents' => 12000],
        ]);

        self::assertArrayHasKey('error', $data);
        self::assertSame(10000, $this->amountOf('bars_october'));
    }

    public function testSplitRequiresTheMotherAndTheChildren(): void
    {
        $this->loadFixtures('envelope_split.yaml');
        $this->loginFixtureUser();
        $tool = self::getContainer()->get(ManageEnvelopesTool::class);

        $missingChildren = json_decode($tool('split', envelopeId: (string) $this->getFixture('leisure_october')->getId()), true, 512, JSON_THROW_ON_ERROR);
        $missingMother = json_decode($tool('split', children: []), true, 512, JSON_THROW_ON_ERROR);
        $badAmount = $this->splitWith('leisure_october', [
            ['envelopeId' => (string) $this->getFixture('bars_october')->getId(), 'amountCents' => -1],
        ]);

        self::assertArrayHasKey('error', $missingChildren);
        self::assertArrayHasKey('error', $missingMother);
        self::assertArrayHasKey('error', $badAmount);
        self::assertSame(10000, $this->amountOf('bars_october'));
    }

    public function testSplitRefusesAnEnvelopeThatIsNotADaughterOrNotMine(): void
    {
        $this->loadFixtures('envelope_split.yaml');
        $this->loginFixtureUser();

        foreach (['food_october', 'bars_november', 'other_leisure_october'] as $stranger) {
            $data = $this->splitWith('leisure_october', [
                ['envelopeId' => (string) $this->getFixture($stranger)->getId(), 'amountCents' => 1000],
            ]);
            self::assertArrayHasKey('error', $data, $stranger);
        }

        $foreignMother = $this->splitWith('other_leisure_october', []);
        self::assertArrayHasKey('error', $foreignMother);
        self::assertSame(9000, $this->amountOf('other_leisure_october'));
    }
}
