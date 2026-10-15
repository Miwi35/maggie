<?php

namespace Maggie\Finance\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Enum\QualificationKind;
use Maggie\Finance\UseCase\ApplyCategorizationRules;
use Maggie\Finance\UseCase\AttachRecurringTransactions;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * The qualification over REST (MAG-476): carried by the series and the rule,
 * copied whole onto the transactions they recognise, set by hand on the
 * transaction, and taken again by the transactions when the operation
 * changes — except those qualified by hand.
 */
class QualificationApiTest extends WebTestCase
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
        $this->loadFixtures(__DIR__.'/../UseCase/fixtures/qualification.yaml');
    }

    public function testALineAttachedToASeriesCarriesItsWholeQualification(): void
    {
        $this->qualifyFromOperations();

        $line = $this->reload('jan_loan');
        self::assertSame('Crédits', $line->getCategory()?->getName());
        self::assertTrue($line->getQualification()->isMandatory());
        self::assertSame(QualificationKind::Repayment, $line->getQualification()->getKind());
        self::assertSame('series', $line->getQualifiedBy());
        self::assertSame((string) $this->getFixture('car_loan')->getId(), (string) $line->getQualifiedBySeries()?->getId());

        $fuel = $this->reload('fuel');
        self::assertSame('Transport', $fuel->getCategory()?->getName());
        self::assertTrue($fuel->getQualification()->isMandatory());
        self::assertSame('rule', $fuel->getQualifiedBy());
        self::assertSame((string) $this->getFixture('rule_fuel')->getId(), (string) $fuel->getQualifiedByRule()?->getId());
    }

    public function testATransactionIsServedWithItsQualificationAndWhoSetIt(): void
    {
        $this->qualifyFromOperations();
        $this->login();

        $data = $this->send('PATCH', $this->iri('transactions', 'jan_loan'), ['note' => 'Échéance de janvier']);

        self::assertResponseIsSuccessful();
        self::assertSame(['mandatory' => true, 'kind' => 'repayment'], self::parameters($data));
        self::assertSame('series', $data['qualifiedBy']);
        self::assertSame($this->iri('recurring_operations', 'car_loan'), $data['qualifiedBySeries']);
        self::assertNull($data['qualifiedByRule'] ?? null);
    }

    public function testChangingTheSeriesCategoryRequalifiesItsLinesExceptOneQualifiedByHand(): void
    {
        $this->qualifyFromOperations();
        $this->login();
        $this->send('PATCH', $this->iri('transactions', 'feb_loan'), ['qualification' => ['mandatory' => false, 'kind' => 'loan']]);
        self::assertResponseIsSuccessful();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->send('PATCH', $this->iri('recurring_operations', 'car_loan'), ['category' => $this->iri('categories', 'groceries')]);

        self::assertResponseIsSuccessful();
        $followed = $this->reload('jan_loan');
        self::assertSame('Courses', $followed->getCategory()?->getName());
        self::assertSame(CategorySource::Series, $followed->getCategorySource());
        $byHand = $this->reload('feb_loan');
        self::assertSame('Crédits', $byHand->getCategory()?->getName());
        self::assertSame(QualificationKind::Loan, $byHand->getQualification()->getKind());
        self::assertSame('manual', $byHand->getQualifiedBy());

        $this->assertMercureUpdatePublished('/transactions/'.$this->getFixture('jan_loan')->getId());
        $this->assertNothingPublishedOn('/transactions/'.$this->getFixture('feb_loan')->getId());
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, (string) $this->getFixture('jan_loan')->getId());
    }

    public function testALineTakenOutOfItsSeriesNoLongerFollowsIt(): void
    {
        $this->qualifyFromOperations();
        $this->login();
        $this->send('PATCH', $this->iri('transactions', 'feb_loan'), ['recurringOperation' => null]);
        self::assertResponseIsSuccessful();
        $this->resetMercure();

        $this->send('PATCH', $this->iri('recurring_operations', 'car_loan'), ['category' => $this->iri('categories', 'groceries')]);

        self::assertResponseIsSuccessful();
        self::assertSame('Courses', $this->reload('jan_loan')->getCategory()?->getName());
        $detached = $this->reload('feb_loan');
        self::assertSame('Crédits', $detached->getCategory()?->getName(), 'it keeps what it inherited');
        self::assertSame(QualificationKind::Repayment, $detached->getQualification()->getKind());
        $this->assertNothingPublishedOn('/transactions/'.$this->getFixture('feb_loan')->getId());
    }

    public function testChangingTheSeriesQualificationAloneReachesItsLines(): void
    {
        $this->qualifyFromOperations();
        $this->login();

        $data = $this->send('PATCH', $this->iri('recurring_operations', 'car_loan'), ['qualification' => ['mandatory' => false, 'kind' => 'loan']]);

        self::assertResponseIsSuccessful();
        self::assertSame(['mandatory' => false, 'kind' => 'loan'], self::parameters($data));
        $series = $this->reloadOperation(RecurringOperation::class, 'car_loan');
        self::assertSame(QualificationKind::Loan, $series->getQualification()->getKind());
        self::assertSame('Crédits', $series->getCategory()->getName(), 'the category stays');
        foreach (['jan_loan', 'feb_loan'] as $fixture) {
            $line = $this->reload($fixture);
            self::assertFalse($line->getQualification()->isMandatory());
            self::assertSame(QualificationKind::Loan, $line->getQualification()->getKind());
            self::assertSame('Crédits', $line->getCategory()?->getName());
        }

        $this->assertMercureUpdatePublished('/recurring_operations/');
        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatchedFor(RecurringOperation::class, (string) $this->getFixture('car_loan')->getId());
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, (string) $this->getFixture('feb_loan')->getId());
    }

    public function testChangingARuleQualificationReachesTheLinesItFiled(): void
    {
        $this->qualifyFromOperations();
        $this->login();

        $this->send('PATCH', $this->iri('categorization_rules', 'rule_fuel'), ['qualification' => ['mandatory' => false, 'kind' => 'none']]);

        self::assertResponseIsSuccessful();
        self::assertFalse($this->reloadOperation(CategorizationRule::class, 'rule_fuel')->getQualification()->isMandatory());
        $fuel = $this->reload('fuel');
        self::assertFalse($fuel->getQualification()->isMandatory());
        self::assertSame('rule', $fuel->getQualifiedBy());
        $this->assertMercureUpdatePublished('/transactions/'.$this->getFixture('fuel')->getId());
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, (string) $this->getFixture('fuel')->getId());
    }

    public function testAQualificationSetOnTheTransactionIsTheOwnersOwn(): void
    {
        $this->qualifyFromOperations();
        $this->login();

        $data = $this->send('PATCH', $this->iri('transactions', 'fuel'), ['qualification' => ['mandatory' => false, 'kind' => 'none']]);

        self::assertResponseIsSuccessful();
        self::assertSame('manual', $data['qualifiedBy']);
        $fuel = $this->reload('fuel');
        self::assertSame(CategorySource::Manual, $fuel->getCategorySource());
        self::assertFalse($fuel->getQualification()->isMandatory());
        self::assertSame('Transport', $fuel->getCategory()?->getName(), 'the category stays');
        self::assertNull($fuel->getQualifiedByRule());
        $this->assertMercureUpdatePublished('/transactions/'.$this->getFixture('fuel')->getId());
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, (string) $this->getFixture('fuel')->getId());
    }

    public function testResendingTheStoredQualificationDoesNotSealItByHand(): void
    {
        $this->qualifyFromOperations();
        $this->login();

        $this->send('PATCH', $this->iri('transactions', 'jan_loan'), ['qualification' => ['mandatory' => true, 'kind' => 'repayment']]);

        self::assertResponseIsSuccessful();
        self::assertSame('series', $this->reload('jan_loan')->getQualifiedBy());
    }

    public function testAPartialQualificationIsRefused(): void
    {
        $this->qualifyFromOperations();
        $this->login();

        $this->send('PATCH', $this->iri('transactions', 'jan_loan'), ['qualification' => ['mandatory' => false]]);

        self::assertResponseStatusCodeSame(400);
        $line = $this->reload('jan_loan');
        self::assertTrue($line->getQualification()->isMandatory());
        self::assertSame('series', $line->getQualifiedBy());
    }

    public function testAnUnknownKindIsRefused(): void
    {
        $this->login();

        $this->send('PATCH', $this->iri('recurring_operations', 'car_loan'), ['qualification' => ['mandatory' => true, 'kind' => 'debt']]);

        self::assertResponseStatusCodeSame(400);
        self::assertSame(QualificationKind::Repayment, $this->reloadOperation(RecurringOperation::class, 'car_loan')->getQualification()->getKind());
    }

    public function testAnUnauthenticatedQualificationIsRefused(): void
    {
        $this->send('PATCH', $this->iri('transactions', 'fuel'), ['qualification' => ['mandatory' => true, 'kind' => 'saving']], authenticated: false);

        self::assertResponseStatusCodeSame(401);
        self::assertSame(QualificationKind::None, $this->reload('fuel')->getQualification()->getKind());
    }

    public function testASeriesIsCreatedWithItsQualification(): void
    {
        $this->login();

        $data = $this->send('POST', '/api/recurring_operations', [
            'label' => 'Prêt étudiant',
            'counterpartyName' => 'Banque Études',
            'category' => $this->iri('categories', 'transport'),
            'account' => $this->iri('accounts', 'checking'),
            'anchorOn' => '2027-01-10',
            'referenceAmountCents' => -10000,
            'qualification' => ['mandatory' => true, 'kind' => 'repayment'],
        ]);

        self::assertResponseStatusCodeSame(201);
        self::assertSame(['mandatory' => true, 'kind' => 'repayment'], self::parameters($data));
        $this->assertMercureUpdatePublished('/recurring_operations/');
        $this->assertElasticsearchIndexDispatched(RecurringOperation::class);
    }

    public function testAQualificationLeftOutOnCreationIsTheCategorysUntilTheClientsSendOne(): void
    {
        $this->login();

        $data = $this->send('POST', '/api/categorization_rules', [
            'labelPattern' => 'CARREFOUR',
            'category' => $this->iri('categories', 'groceries'),
        ]);

        self::assertResponseStatusCodeSame(201);
        self::assertSame(['mandatory' => true, 'kind' => 'none'], self::parameters($data));
    }

    /**
     * The qualification of a JSON-LD body, without its `@var`.
     *
     * @param array<string, mixed> $data
     *
     * @return array<string, mixed>
     */
    private static function parameters(array $data): array
    {
        return array_filter($data['qualification'], static fn (string $key) => !str_starts_with($key, '@'), ARRAY_FILTER_USE_KEY);
    }

    /** What the series and the rules do as the lines land: the attachment, then the catch-up of the rules. */
    private function qualifyFromOperations(): void
    {
        $user = $this->getFixture('test_user');
        self::getContainer()->get(AttachRecurringTransactions::class)->execute($user);
        self::getContainer()->get(ApplyCategorizationRules::class)->execute($user);
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function login(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));
    }

    private function iri(string $collection, string $fixture): string
    {
        return '/api/'.$collection.'/'.$this->getFixture($fixture)->getId();
    }

    /**
     * @param array<string, mixed> $body
     *
     * @return array<string, mixed>
     */
    private function send(string $method, string $uri, array $body = [], bool $authenticated = true): array
    {
        $headers = [
            'CONTENT_TYPE' => 'PATCH' === $method ? 'application/merge-patch+json' : 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ];

        $this->client->request($method, $uri, [], [], $authenticated ? array_merge($headers, $this->authHeaders()) : $headers, [] === $body ? null : json_encode($body, JSON_THROW_ON_ERROR));

        $content = (string) $this->client->getResponse()->getContent();

        return '' === $content ? [] : json_decode($content, true, 512, JSON_THROW_ON_ERROR);
    }

    private function reload(string $fixture): Transaction
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        /** @var Transaction $transaction */
        $transaction = $em->find(Transaction::class, $this->getFixture($fixture)->getId());

        return $transaction;
    }

    /**
     * @template T of object
     *
     * @param class-string<T> $class
     *
     * @return T
     */
    private function reloadOperation(string $class, string $fixture): object
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        /** @var T $operation */
        $operation = $em->find($class, $this->getFixture($fixture)->getId());

        return $operation;
    }
}
