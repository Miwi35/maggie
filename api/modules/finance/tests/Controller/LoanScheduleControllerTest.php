<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class LoanScheduleControllerTest extends WebTestCase
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
    private function schedule(string $id, bool $authenticated = true): array
    {
        if ($authenticated) {
            $this->authenticateAsUser($this->getFixture('test_user'));
        }

        $this->client->request('GET', \sprintf('/api/finance/loans/%s/schedule', $id), [], [], $authenticated ? $this->authHeaders() : []);

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->schedule((string) $this->getFixture('mortgage')->getId(), authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testTheCapitalOwedFallsToZeroAtTheLastPayment(): void
    {
        $data = $this->schedule((string) $this->getFixture('mortgage')->getId());

        self::assertResponseIsSuccessful();
        self::assertSame(9800000, $data['principalRemainingCents']);
        self::assertSame(\count($data['months']), $data['monthsRemaining']);
        self::assertSame($data['months'][array_key_last($data['months'])]['month'], $data['freedOn']);
        self::assertSame(0, $data['months'][array_key_last($data['months'])]['remainingCents']);
        self::assertLessThan(9800000, $data['months'][0]['remainingCents']);
        self::assertGreaterThan(0, $data['totalInterestCents']);
        self::assertSame(['month', 'remainingCents'], array_keys($data['months'][0]));
    }

    public function testAnotherUsersLoanIsNotFound(): void
    {
        $this->schedule((string) $this->getFixture('foreign')->getId());

        self::assertResponseStatusCodeSame(404);
    }

    public function testAnIdThatIsNotALoanIsNotFound(): void
    {
        $this->schedule('not-a-ulid');

        self::assertResponseStatusCodeSame(404);
    }
}
