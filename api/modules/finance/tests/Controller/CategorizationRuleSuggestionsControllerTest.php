<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Entity\Transaction;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * Rules derived from the statement itself: what the history implies, and what
 * only happens once the user says yes.
 */
class CategorizationRuleSuggestionsControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetAsyncTransport();
    }

    /** @return list<array<string, mixed>> */
    private function suggestions(): array
    {
        $this->client->request(
            'GET',
            '/api/finance/categorization-rules/suggestions',
            [],
            [],
            $this->authHeaders(),
        );

        return json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR)['suggestions'];
    }

    /** @return list<string> */
    private function patternsMentioning(string $word): array
    {
        return array_values(array_filter(
            array_column($this->suggestions(), 'pattern'),
            static fn (string $pattern) => str_contains($pattern, $word),
        ));
    }

    /** @param list<array<string, mixed>> $rules */
    private function accept(array $rules): array
    {
        $this->client->request(
            'POST',
            '/api/finance/categorization-rules/suggestions',
            [],
            [],
            $this->authHeaders() + ['CONTENT_TYPE' => 'application/json'],
            json_encode(['rules' => $rules], JSON_THROW_ON_ERROR),
        );

        return json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('GET', '/api/finance/categorization-rules/suggestions');

        self::assertResponseStatusCodeSame(401);
    }

    public function testItProposesAPatternForEachMerchantThatComesBack(): void
    {
        $this->loadFixtures('rule_suggestions.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $patterns = array_column($this->suggestions(), 'pattern');

        self::assertContains('CARREFOUR DAC VL', $patterns);
        self::assertContains('INTERMARCHE ESSENCE', $patterns);

        // Seen once: it may never come back, and a rule for it is noise.
        self::assertNotContains('HUISSIER DE JUS PIPR', $patterns);
    }

    public function testASuggestionCarriesWhatItIsWorth(): void
    {
        $this->loadFixtures('rule_suggestions.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $suggestions = array_column($this->suggestions(), null, 'pattern');
        $groceries = $suggestions['CARREFOUR DAC VL'];

        self::assertSame(2, $groceries['occurrences']);
        self::assertSame(-14515, $groceries['totalCents']);
        self::assertSame('debit', $groceries['direction']);
        self::assertNotEmpty($groceries['samples']);
    }

    public function testFuelAtASupermarketIsNotFiledAsGroceries(): void
    {
        $this->loadFixtures('rule_suggestions.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $suggestions = array_column($this->suggestions(), null, 'pattern');

        self::assertSame('Nourriture', $suggestions['CARREFOUR DAC VL']['categoryName']);
        // The brand sells both; the label says which one this was.
        self::assertSame('Essence', $suggestions['INTERMARCHE ESSENCE']['categoryName']);
    }

    public function testReadingSuggestionsWritesNothing(): void
    {
        $this->loadFixtures('rule_suggestions.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->suggestions();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertCount(0, $em->getRepository(CategorizationRule::class)->findAll());
    }

    public function testAcceptingCreatesTheRuleAndFilesTheHistoryUnderIt(): void
    {
        $this->loadFixtures('rule_suggestions.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $suggestions = array_column($this->suggestions(), null, 'pattern');
        $result = $this->accept([[
            'pattern' => 'CARREFOUR DAC VL',
            'categoryId' => $suggestions['CARREFOUR DAC VL']['categoryId'],
            'direction' => 'debit',
        ]]);

        self::assertResponseIsSuccessful();
        self::assertSame(1, $result['created']);
        // A rule the user cannot see working is a rule they do not trust.
        self::assertSame(2, $result['categorized']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $transaction = $em->getRepository(Transaction::class)
            ->findOneBy(['label' => 'PAIEMENT PAR CARTE X9633 MP*CARREFOUR DAC VL 04/08']);
        self::assertSame('Nourriture', $transaction->getCategory()->getName());

        $this->assertElasticsearchIndexDispatched(CategorizationRule::class);
    }

    public function testASpecificPatternOutranksTheBrandItContains(): void
    {
        $this->loadFixtures('rule_suggestions.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $suggestions = array_column($this->suggestions(), null, 'pattern');
        $this->accept([
            ['pattern' => 'INTERMARCHE', 'categoryId' => $suggestions['CARREFOUR DAC VL']['categoryId']],
            [
                'pattern' => 'INTERMARCHE ESSENCE',
                'categoryId' => $suggestions['INTERMARCHE ESSENCE']['categoryId'],
            ],
        ]);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $fuel = $em->getRepository(Transaction::class)
            ->findOneBy(['label' => 'PAIEMENT PAR CARTE X9633 INTERMARCHE ESSENCE 30/07']);

        // Both rules claim it; the one that says more has to win.
        self::assertSame('Essence', $fuel->getCategory()->getName());
    }

    public function testAMerchantAlreadyCoveredByARuleIsNotSuggestedAgain(): void
    {
        $this->loadFixtures('rule_suggestions.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $suggestions = array_column($this->suggestions(), null, 'pattern');
        $this->accept([[
            'pattern' => 'CARREFOUR DAC VL',
            'categoryId' => $suggestions['CARREFOUR DAC VL']['categoryId'],
        ]]);

        self::assertNotContains('CARREFOUR DAC VL', array_column($this->suggestions(), 'pattern'));
    }

    public function testAMerchantWhoseLinesAreAlreadyFiledIsNotSuggested(): void
    {
        $this->loadFixtures('rule_suggestions_noise.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        // The "LIDL" rule already files them: a second rule would change nothing.
        self::assertSame([], $this->patternsMentioning('LIDL'));
    }

    public function testMoneyMovedBetweenOwnAccountsIsNotSuggested(): void
    {
        $this->loadFixtures('rule_suggestions_noise.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        // Saving is not spending: a heading for it would count it twice.
        self::assertSame([], $this->patternsMentioning('LIVRET'));
    }

    public function testOnlyTheMerchantsStillToFileAreSuggested(): void
    {
        $this->loadFixtures('rule_suggestions_noise.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $suggestions = array_column($this->suggestions(), null, 'pattern');

        self::assertEqualsCanonicalizing(['NETFLIX.COM', 'LE FOURNIL JANZE'], array_keys($suggestions));
        self::assertSame('TV & streaming', $suggestions['NETFLIX.COM']['categoryName']);
    }

    public function testTheHeadingAlreadyGivenToAMerchantIsTheOneProposed(): void
    {
        $this->loadFixtures('rule_suggestions_noise.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $bakery = array_column($this->suggestions(), null, 'pattern')['LE FOURNIL JANZE'];

        // No dictionary knows this shop; the owner's own answer does.
        self::assertSame('Nourriture', $bakery['categoryName']);
        // Only the lines still to file are counted.
        self::assertSame(2, $bakery['occurrences']);
        self::assertSame(-1230, $bakery['totalCents']);
    }

    public function testAnEmptyAcceptanceIsRefused(): void
    {
        $this->loadFixtures('rule_suggestions.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->accept([]);

        self::assertResponseStatusCodeSame(400);
    }

    public function testACategoryThatIsNotYoursCreatesNothing(): void
    {
        $this->loadFixtures('rule_suggestions.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->accept([['pattern' => 'CARREFOUR', 'categoryId' => '01JBKQZ0000000000000000000']]);

        self::assertResponseStatusCodeSame(400);
    }
}
