<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Entity\Envelope;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class EnvelopeSplitControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /** @param array<string, mixed> $body */
    private function split(string $envelopeId, array $body): void
    {
        $this->client->request('PUT', "/api/finance/envelopes/{$envelopeId}/split", [], [], array_merge([
            'CONTENT_TYPE' => 'application/json',
        ], $this->authHeaders()), json_encode($body, JSON_THROW_ON_ERROR));
    }

    /** @return array<string, mixed> */
    private function json(): array
    {
        return json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function amountOf(string $fixture): int
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->find(Envelope::class, $this->getFixture($fixture)->getId())->getAmountCents();
    }

    private function id(string $fixture): string
    {
        return (string) $this->getFixture($fixture)->getId();
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->loadFixtures('envelope_split.yaml');

        $this->client->request('PUT', '/api/finance/envelopes/'.$this->id('leisure_october').'/split', [], [], [
            'CONTENT_TYPE' => 'application/json',
        ], json_encode(['children' => []], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    public function testMissingChildrenReturns400(): void
    {
        $this->loadFixtures('envelope_split.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->split($this->id('leisure_october'), []);

        self::assertResponseStatusCodeSame(400);
    }

    public function testMalformedChildReturns400(): void
    {
        $this->loadFixtures('envelope_split.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->split($this->id('leisure_october'), ['children' => [['envelopeId' => $this->id('bars_october'), 'amountCents' => -5]]]);
        self::assertResponseStatusCodeSame(400);

        $this->split($this->id('leisure_october'), ['children' => [['envelopeId' => $this->id('bars_october'), 'amountCents' => '12000']]]);
        self::assertResponseStatusCodeSame(400);

        $this->split($this->id('leisure_october'), ['children' => [['amountCents' => 12000]]]);
        self::assertResponseStatusCodeSame(400);

        self::assertSame(10000, $this->amountOf('bars_october'));
    }

    public function testUnknownOrForeignEnvelopeReturns404(): void
    {
        $this->loadFixtures('envelope_split.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->split($this->id('other_leisure_october'), ['children' => []]);
        self::assertResponseStatusCodeSame(404);

        $this->split('01JZZZZZZZZZZZZZZZZZZZZZZZ', ['children' => []]);
        self::assertResponseStatusCodeSame(404);
    }

    public function testMovingTheBoundaryBetweenTwoSistersLeavesTheRestFree(): void
    {
        $this->loadFixtures('envelope_split.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->split($this->id('leisure_october'), ['children' => [
            ['envelopeId' => $this->id('bars_october'), 'amountCents' => 12000],
            ['envelopeId' => $this->id('cinema_october'), 'amountCents' => 3000],
        ]]);

        self::assertResponseIsSuccessful();
        $data = $this->json();
        self::assertSame($this->id('leisure_october'), $data['envelopeId']);
        self::assertSame(30000, $data['amountCents']);
        self::assertSame(15000, $data['allocatedToChildrenCents']);
        self::assertSame(15000, $data['freeCents']);
        $byName = array_column($data['children'], null, 'categoryName');
        self::assertSame(12000, $byName['Bars']['amountCents']);
        self::assertSame(3000, $byName['Cinéma']['amountCents']);

        self::assertSame(12000, $this->amountOf('bars_october'));
        self::assertSame(3000, $this->amountOf('cinema_october'));
        self::assertSame(30000, $this->amountOf('leisure_october'));

        $this->assertMercurePublishedOnce('/envelopes/'.$this->id('bars_october'));
        $this->assertMercurePublishedOnce('/envelopes/'.$this->id('cinema_october'));
        $this->assertNothingPublishedOn('/envelopes/'.$this->id('leisure_october'));
        $this->assertElasticsearchIndexDispatched(Envelope::class);
    }

    public function testOnlyTheEnvelopesThatChangedArePublished(): void
    {
        $this->loadFixtures('envelope_split.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->split($this->id('leisure_october'), ['children' => [
            ['envelopeId' => $this->id('bars_october'), 'amountCents' => 10000],
            ['envelopeId' => $this->id('cinema_october'), 'amountCents' => 4000],
        ]]);

        self::assertResponseIsSuccessful();
        $this->assertMercurePublishedOnce('/envelopes/'.$this->id('cinema_october'));
        $this->assertNothingPublishedOn('/envelopes/'.$this->id('bars_october'));
    }

    public function testASisterLeftOutKeepsItsAmountAndCountsInTheTotal(): void
    {
        $this->loadFixtures('envelope_split.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        // Cinéma keeps its 50 €: Bars may take 250 € at most.
        $this->split($this->id('leisure_october'), ['children' => [
            ['envelopeId' => $this->id('bars_october'), 'amountCents' => 25000],
        ]]);

        self::assertResponseIsSuccessful();
        self::assertSame(0, $this->json()['freeCents']);
        self::assertSame(25000, $this->amountOf('bars_october'));
        self::assertSame(5000, $this->amountOf('cinema_october'));
    }

    public function testASplitBeyondTheMotherIsRefusedAndNothingChanges(): void
    {
        $this->loadFixtures('envelope_split.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->split($this->id('leisure_october'), ['children' => [
            ['envelopeId' => $this->id('bars_october'), 'amountCents' => 25000],
            ['envelopeId' => $this->id('cinema_october'), 'amountCents' => 5001],
        ]]);

        self::assertResponseStatusCodeSame(400);
        $data = $this->json();
        self::assertArrayHasKey('error', $data);
        // The answer carries the current state, nothing deducted.
        self::assertSame(15000, $data['allocatedToChildrenCents']);
        self::assertSame(15000, $data['freeCents']);
        $byName = array_column($data['children'], null, 'categoryName');
        self::assertSame(10000, $byName['Bars']['amountCents']);
        self::assertSame(5000, $byName['Cinéma']['amountCents']);

        self::assertSame(10000, $this->amountOf('bars_october'));
        self::assertSame(5000, $this->amountOf('cinema_october'));
        $this->assertMercureUpdateCount(0);
    }

    public function testAnEnvelopeThatIsNotADirectDaughterInTheSamePeriodIsRefused(): void
    {
        $this->loadFixtures('envelope_split.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        foreach (['bars_november', 'food_october', 'other_leisure_october', 'leisure_october'] as $stranger) {
            $this->split($this->id('leisure_october'), ['children' => [
                ['envelopeId' => $this->id($stranger), 'amountCents' => 1000],
            ]]);

            self::assertResponseStatusCodeSame(400, $stranger);
        }

        self::assertSame(8000, $this->amountOf('bars_november'));
        self::assertSame(40000, $this->amountOf('food_october'));
        self::assertSame(9000, $this->amountOf('other_leisure_october'));
        self::assertSame(30000, $this->amountOf('leisure_october'));
    }

    public function testTheSameEnvelopeTwiceReturns400(): void
    {
        $this->loadFixtures('envelope_split.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->split($this->id('leisure_october'), ['children' => [
            ['envelopeId' => $this->id('bars_october'), 'amountCents' => 1000],
            ['envelopeId' => $this->id('bars_october'), 'amountCents' => 2000],
        ]]);

        self::assertResponseStatusCodeSame(400);
        self::assertSame(10000, $this->amountOf('bars_october'));
    }
}
