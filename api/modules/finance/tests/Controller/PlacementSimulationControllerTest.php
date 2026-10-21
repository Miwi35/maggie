<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** GET /api/finance/placements/{id}/simulation: the curve the clients draw (MAG-555). */
class PlacementSimulationControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures(__DIR__.'/../Api/fixtures/placement.yaml');
    }

    /** @return array<string, mixed> */
    private function simulate(string $id, bool $authenticated = true): array
    {
        if ($authenticated) {
            $this->authenticateAsUser($this->getFixture('test_user'));
        }

        $this->client->request('GET', \sprintf('/api/finance/placements/%s/simulation', $id), [], [], $authenticated ? $this->authHeaders() : []);

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->simulate((string) $this->getFixture('pea')->getId(), authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testThePeaMonthByMonth(): void
    {
        $data = $this->simulate((string) $this->getFixture('pea')->getId());

        self::assertResponseIsSuccessful();
        self::assertSame(['final', 'paid', 'interest', 'rent', 'curve'], array_keys($data));
        self::assertEqualsWithDelta(7443007, $data['final'], 100);
        self::assertSame(200000 + 240 * 15000, $data['paid']);
        self::assertCount(241, $data['curve']);
        self::assertSame(['month' => 0, 'capital' => 200000, 'paid' => 200000], $data['curve'][0]);
        self::assertSame($data['final'], $data['curve'][240]['capital']);
    }

    public function testAnotherUsersPlacementIsNotFound(): void
    {
        $this->simulate((string) $this->getFixture('secret')->getId());

        self::assertResponseStatusCodeSame(404);
    }

    public function testAnIdThatIsNotAUlidIsNotFound(): void
    {
        $this->simulate('not-a-ulid');

        self::assertResponseStatusCodeSame(404);
    }
}
