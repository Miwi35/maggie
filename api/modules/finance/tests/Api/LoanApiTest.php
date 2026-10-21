<?php

namespace Maggie\Finance\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Enum\LoanType;
use Maggie\Finance\Enum\PartialRepaymentEffect;
use Maggie\Finance\Enum\PrepaymentPenaltyMode;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class LoanApiTest extends WebTestCase
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
    private function post(array $body, bool $authenticated = true): void
    {
        $headers = [
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ];

        $this->client->request('POST', '/api/loans', [], [], $authenticated
            ? array_merge($headers, $this->authHeaders())
            : $headers, json_encode($body, JSON_THROW_ON_ERROR));
    }

    public function testCreateLoanRequiresAuthentication(): void
    {
        $this->loadFixtures('loan.yaml');

        $this->post([
            'name' => 'Prêt travaux',
            'principalRemainingCents' => 600000,
            'monthlyPaymentCents' => 50000,
        ], authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testCreateLoanPersistsPublishesAndIndexes(): void
    {
        $this->loadFixtures('loan.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $this->post([
            'name' => 'Prêt travaux',
            'lender' => 'CIC',
            'principalRemainingCents' => 600000,
            'monthlyPaymentCents' => 50000,
            'annualRateBasisPoints' => 350,
            'priority' => 1,
            'currency' => 'EUR',
        ]);

        self::assertResponseStatusCodeSame(201);

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame('Prêt travaux', $data['name']);
        self::assertSame(350, $data['annualRateBasisPoints']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertCount(3, $em->getRepository(Loan::class)->findAll());

        $this->assertMercureUpdatePublished('/loans/');
        $this->assertElasticsearchIndexDispatched(Loan::class);
    }

    public function testALoanWithoutNameIsRejected(): void
    {
        $this->loadFixtures('loan.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $this->post(['principalRemainingCents' => 600000, 'monthlyPaymentCents' => 50000]);

        self::assertResponseStatusCodeSame(422);
    }

    public function testALoanThatWouldNeverBeRepaidIsRejected(): void
    {
        $this->loadFixtures('loan.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $this->post([
            'name' => 'Piège',
            'principalRemainingCents' => 1000000,
            'monthlyPaymentCents' => 2000,
            'annualRateBasisPoints' => 500,
        ]);

        self::assertResponseStatusCodeSame(422);
    }

    /** @param array<string, mixed> $body */
    private function patch(string $id, array $body, bool $authenticated = true): void
    {
        $headers = [
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ];

        $this->client->request('PATCH', '/api/loans/'.$id, [], [], $authenticated
            ? array_merge($headers, $this->authHeaders())
            : $headers, json_encode($body, JSON_THROW_ON_ERROR));
    }

    /**
     * The admin sends the whole record read back, its `id` an IRI and an
     * `originId` added (MAG-480: every edit from the admin must save).
     */
    public function testPatchSendingBackTheWholeRecordReadBeforeSavesIt(): void
    {
        $this->loadFixtures('loan.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);
        $id = (string) $this->getFixture('car')->getId();

        $this->client->request('GET', '/api/loans/'.$id, [], [], array_merge([
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()));
        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        $body['id'] = $body['@id'];
        $body['originId'] = $id;
        $body['name'] = 'Crédit renommé';

        $this->patch($id, $body);

        self::assertResponseIsSuccessful();
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertSame('Crédit renommé', $em->find(Loan::class, $id)->getName());
        $this->assertMercureUpdatePublished('/loans/');
        $this->assertElasticsearchIndexDispatched(Loan::class);
    }

    public function testPatchLoanRequiresAuthentication(): void
    {
        $this->loadFixtures('loan.yaml');
        $loan = $this->getFixture('car');

        $this->patch((string) $loan->getId(), ['lender' => null], authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testPatchWithNullLenderClearsIt(): void
    {
        $this->loadFixtures('loan.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);
        $loan = $this->getFixture('car');

        $this->patch((string) $loan->getId(), ['lender' => null]);

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $refreshed = $em->find(Loan::class, $loan->getId());
        self::assertNull($refreshed->getLender());
        self::assertSame(240000, $refreshed->getPrincipalRemainingCents());
        self::assertSame(20000, $refreshed->getMonthlyPaymentCents());

        $this->assertMercureUpdatePublished('/loans/');
        $this->assertElasticsearchIndexDispatched(Loan::class);
    }

    public function testDeleteLoanRemovesAndPublishes(): void
    {
        $this->loadFixtures('loan.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $loan = $this->getFixture('car');
        $loanId = $loan->getId();

        $this->client->request('DELETE', '/api/loans/'.$loanId, [], [], array_merge([
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()));

        self::assertResponseStatusCodeSame(204);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->find(Loan::class, $loanId));

        $this->assertMercureUpdatePublished('/loans/');
        $this->assertElasticsearchDeleteDispatched('loans');
    }

    private function login(): void
    {
        $this->loadFixtures('loan.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);
    }

    private function rows(): int
    {
        return \count(self::getContainer()->get('doctrine.orm.entity_manager')->getRepository(Loan::class)->findAll());
    }

    public function testCreateADetailedLoanPersistsItsTypeAndConditions(): void
    {
        $this->login();

        $this->post([
            'name' => 'Prêt relais',
            'principalRemainingCents' => 1000000,
            'monthlyPaymentCents' => 2500,
            'annualRateBasisPoints' => 300,
            'type' => 'bridge',
            'projected' => true,
            'initialAmountCents' => 1000000,
            'aprBasisPoints' => 345,
            'durationMonths' => 12,
            'firstPaymentOn' => '2026-11-05',
            'periodicity' => 'monthly',
            'insuranceMonthlyCents' => 1200,
            'feesCents' => 80000,
            'prepaymentPenaltyMode' => 'exempt',
            'partialRepaymentEffect' => 'lower_payment',
            'noticeDays' => 30,
        ]);

        self::assertResponseStatusCodeSame(201);
        $data = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame('bridge', $data['type']);
        self::assertTrue($data['projected']);
        self::assertSame('2026-11-05', $data['firstPaymentOn']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $loan = $em->getRepository(Loan::class)->findOneBy(['name' => 'Prêt relais']);
        self::assertNotNull($loan);
        self::assertSame(LoanType::Bridge, $loan->getType());
        self::assertTrue($loan->isProjected());
        self::assertSame(12, $loan->getDurationMonths());
        self::assertSame('2026-11-05', $loan->getFirstPaymentOn()?->format('Y-m-d'));
        self::assertSame(1200, $loan->getInsuranceMonthlyCents());
        self::assertSame(PrepaymentPenaltyMode::Exempt, $loan->getPrepaymentPenaltyMode());
        self::assertSame(PartialRepaymentEffect::LowerPayment, $loan->getPartialRepaymentEffect());
        self::assertTrue($loan->isEarlyRepaymentAllowed());

        $this->assertMercureUpdatePublished('/loans/');
        $this->assertElasticsearchIndexDispatched(Loan::class);
    }

    public function testAnExistingLoanReadsAsAFixedSignedOne(): void
    {
        $this->login();

        $this->client->request('GET', '/api/loans/'.$this->getFixture('car')->getId(), [], [], array_merge([
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()));

        self::assertResponseIsSuccessful();
        $data = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame('amortizing_fixed', $data['type']);
        self::assertFalse($data['projected']);
        self::assertSame('legal_cap', $data['prepaymentPenaltyMode']);
        self::assertTrue($data['earlyRepaymentAllowed']);
    }

    /** @return iterable<string, array{array<string, mixed>, string}> */
    public static function incompleteTypes(): iterable
    {
        yield 'stepped without steps' => [['type' => 'stepped'], 'steps'];
        yield 'stepped with a step from month 0' => [['type' => 'stepped', 'steps' => [['fromMonth' => 0, 'monthlyPaymentCents' => 50000]]], 'step'];
        yield 'variable without its index' => [['type' => 'amortizing_variable', 'marginBasisPoints' => 100], 'referenceIndex'];
        yield 'variable without its margin' => [['type' => 'amortizing_variable', 'referenceIndex' => 'Euribor 3M'], 'marginBasisPoints'];
        yield 'total deferral without its months' => [['type' => 'deferred_total'], 'deferralMonths'];
        yield 'partial deferral of 0 months' => [['type' => 'deferred_partial', 'deferralMonths' => 0], 'deferralMonths'];
        yield 'in fine without its duration' => [['type' => 'in_fine'], 'durationMonths'];
        yield 'a fixed penalty without its amount' => [['prepaymentPenaltyMode' => 'fixed'], 'prepaymentPenaltyFixedCents'];
        yield 'a floor above the cap' => [['rateFloorBasisPoints' => 500, 'rateCapBasisPoints' => 400], 'rateFloorBasisPoints'];
        yield 'an insurance rate without the amount borrowed' => [['insuranceRateBasisPoints' => 30], 'initialAmountCents'];
    }

    /** @param array<string, mixed> $conditions */
    #[\PHPUnit\Framework\Attributes\DataProvider('incompleteTypes')]
    public function testATypeMissingWhatItNeedsIsRefusedWith400(array $conditions, string $named): void
    {
        $this->login();

        $this->post([
            'name' => 'Prêt incomplet',
            'principalRemainingCents' => 600000,
            'monthlyPaymentCents' => 50000,
            'annualRateBasisPoints' => 350,
        ] + $conditions);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString($named, (string) $this->client->getResponse()->getContent());
        self::assertSame(2, $this->rows());
        $this->assertMercureUpdateCount(0);
    }

    public function testAnUnknownTypeIsRefused(): void
    {
        $this->login();

        $this->post(['name' => 'Prêt ballon', 'principalRemainingCents' => 600000, 'monthlyPaymentCents' => 50000, 'type' => 'balloon']);

        self::assertResponseStatusCodeSame(400);
        self::assertSame(2, $this->rows());
    }

    public function testAnInFineLoanIsNotAskedToAmortise(): void
    {
        $this->login();

        // The payment is the interest: an amortized loan would be refused.
        $this->post([
            'name' => 'Prêt in fine',
            'principalRemainingCents' => 1000000,
            'monthlyPaymentCents' => 2500,
            'annualRateBasisPoints' => 300,
            'type' => 'in_fine',
            'durationMonths' => 12,
        ]);

        self::assertResponseStatusCodeSame(201);
    }

    public function testPatchToSteppedWithoutStepsIsRefusedAndKeepsTheLoan(): void
    {
        $this->login();
        $id = (string) $this->getFixture('car')->getId();

        $this->patch($id, ['type' => 'stepped']);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('steps', (string) $this->client->getResponse()->getContent());
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertSame(LoanType::AmortizingFixed, $em->find(Loan::class, $id)->getType());
        $this->assertMercureUpdateCount(0);
    }

    public function testPatchSetsThenClearsACondition(): void
    {
        $this->login();
        $id = (string) $this->getFixture('car')->getId();

        $this->patch($id, ['type' => 'stepped', 'steps' => [
            ['fromMonth' => 7, 'monthlyPaymentCents' => 30000],
            ['fromMonth' => 1, 'monthlyPaymentCents' => 20000],
        ], 'feesCents' => 50000]);
        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $loan = $em->find(Loan::class, $id);
        self::assertSame(LoanType::Stepped, $loan->getType());
        // Kept in the order of the months.
        self::assertSame([1, 7], array_column($loan->getSteps() ?? [], 'fromMonth'));
        self::assertSame(50000, $loan->getFeesCents());
        $this->assertMercureUpdatePublished('/loans/');
        $this->assertElasticsearchIndexDispatched(Loan::class);

        $this->patch($id, ['feesCents' => null]);
        self::assertResponseIsSuccessful();
        $em->clear();
        self::assertNull($em->find(Loan::class, $id)->getFeesCents());
    }
}
