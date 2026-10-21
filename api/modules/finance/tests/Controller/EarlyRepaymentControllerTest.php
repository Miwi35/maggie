<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Enum\PartialRepaymentEffect;
use Maggie\Finance\Service\LoanSchedule;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class EarlyRepaymentControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures('early_repayment.yaml');
    }

    /** @return array<string, mixed> */
    private function simulate(string $ref, string $query = '', bool $authenticated = true): array
    {
        if ($authenticated) {
            $this->authenticateAsUser($this->getFixture('test_user'));
        }

        $this->client->request(
            'GET',
            \sprintf('/api/finance/loans/%s/early-repayment%s', $this->getFixture($ref)->getId(), $query),
            [],
            [],
            $authenticated ? $this->authHeaders() : [],
        );

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->simulate('mortgage', authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testATotalRepaymentAtTheLegalCap(): void
    {
        $data = $this->simulate('mortgage');

        self::assertResponseIsSuccessful();
        /** @var Loan $loan */
        $loan = $this->getFixture('mortgage');
        $interestLeft = array_sum(array_column(
            (new LoanSchedule())->rows($loan, new \DateTimeImmutable('first day of this month')),
            'interestCents',
        ));

        self::assertSame([
            'amountCents' => 9800000,
            'penaltyMode' => 'legal_cap',
            // min(6 months at 1,80 % of 98 000 € = 882 €, 3 % = 2 940 €).
            'penaltyCents' => 88200,
            'remainingInterestCents' => $interestLeft,
            'realGainCents' => $interestLeft - 88200,
        ], $data);
    }

    public function testAPartialRepaymentSavesTheInterestOfTheAmountRepaid(): void
    {
        $total = $this->simulate('mortgage');
        $partial = $this->simulate('mortgage', '?amountCents=2000000');

        self::assertResponseIsSuccessful();
        self::assertSame(2000000, $partial['amountCents']);
        // Six months at 1,80 % of 20 000 €.
        self::assertSame(18000, $partial['penaltyCents']);
        self::assertGreaterThan(0, $partial['remainingInterestCents']);
        self::assertLessThan($total['remainingInterestCents'], $partial['remainingInterestCents']);
    }

    public function testAConsumerLoanWithMoreThanTwelveMonthsLeftCostsOnePercent(): void
    {
        // 10 000 € at 450 € a month: about 24 months left.
        $data = $this->simulate('consumer');

        self::assertResponseIsSuccessful();
        self::assertSame(10000, $data['penaltyCents']);
    }

    public function testALoanThatForbidsItIsRefused(): void
    {
        $data = $this->simulate('locked');

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('earlyRepaymentAllowed', $data['error']);
    }

    public function testAPartialRepaymentUnderTheLendersMinimumIsRefused(): void
    {
        $this->simulate('consumer', '?amountCents=50000');

        self::assertResponseStatusCodeSame(400);
    }

    public function testAnAmountAboveTheCapitalOwedIsRefused(): void
    {
        $this->simulate('mortgage', '?amountCents=9800001');

        self::assertResponseStatusCodeSame(400);
    }

    public function testAnAmountThatIsNotCentsIsRefused(): void
    {
        $this->simulate('mortgage', '?amountCents=12.5');
        self::assertResponseStatusCodeSame(400);

        $this->simulate('mortgage', '?amountCents=0');
        self::assertResponseStatusCodeSame(400);
    }

    public function testAnotherUsersLoanIsNotFound(): void
    {
        $data = $this->simulate('foreign');

        self::assertResponseStatusCodeSame(404);
        self::assertStringNotContainsString('autre', (string) json_encode($data));
    }

    public function testAnIdThatIsNotALoanIsNotFound(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/finance/loans/not-a-ulid/early-repayment', [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(404);
    }

    public function testLoweringThePaymentSavesLessInterestThanShorteningTheLoan(): void
    {
        $shorten = $this->simulate('mortgage', '?amountCents=2000000');

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->find(Loan::class, $this->getFixture('mortgage')->getId())->setPartialRepaymentEffect(PartialRepaymentEffect::LowerPayment);
        $em->flush();
        $lower = $this->simulate('mortgage', '?amountCents=2000000');

        self::assertResponseIsSuccessful();
        self::assertSame($shorten['penaltyCents'], $lower['penaltyCents']);
        self::assertGreaterThan(0, $lower['remainingInterestCents']);
        self::assertLessThan($shorten['remainingInterestCents'], $lower['remainingInterestCents']);
    }
}
