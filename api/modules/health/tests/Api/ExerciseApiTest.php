<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** GET /api/health/exercises and /exercise-ladders (MAG-774): read only, authenticated. */
class ExerciseApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures('daily_energy.yaml');
    }

    /** @return array<string, mixed> */
    private function get(string $path, bool $authenticated = true): array
    {
        if ($authenticated) {
            $user = $this->getFixture('test_user');
            \assert($user instanceof User);
            $this->authenticateAsUser($user);
        }
        $this->client->request('GET', '/api/health'.$path, server: $authenticated ? $this->authHeaders() : []);

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testItRequiresAuthentication(): void
    {
        foreach (['/exercises', '/exercises/pull-up', '/exercise-ladders', '/exercise-ladders/squat'] as $path) {
            $this->get($path, authenticated: false);
            self::assertResponseStatusCodeSame(401, $path);
        }
    }

    public function testItFiltersByPattern(): void
    {
        $body = $this->get('/exercises?pattern=squat');

        self::assertResponseIsSuccessful();
        self::assertNotEmpty($body['member']);
        self::assertSame(['squat'], array_values(array_unique(array_column($body['member'], 'pattern'))));
    }

    public function testEquipmentKeepsWhatIsDoableWithIt(): void
    {
        $slugs = array_column($this->get('/exercises?equipment=weighted_bag')['member'], 'slug');

        self::assertContains('goblet-squat', $slugs);
        self::assertContains('push-up', $slugs);
        self::assertNotContains('pull-up', $slugs);
        self::assertNotContains('weighted-pull-up', $slugs);
    }

    public function testAnUnknownFilterIsA400(): void
    {
        $this->get('/exercises?pattern=nope');
        self::assertResponseStatusCodeSame(400);
        $this->get('/exercises?equipment=barbell');
        self::assertResponseStatusCodeSame(400);
        $this->get('/exercises?ladder=nope');
        self::assertResponseStatusCodeSame(400);
    }

    public function testItReadsOneExerciseWithItsLadderPosition(): void
    {
        $body = $this->get('/exercises/pull-up-negative');

        self::assertResponseIsSuccessful();
        self::assertSame('Tractions négatives', $body['name']);
        self::assertTrue($body['eccentric']);
        self::assertSame(4, $body['ladders'][0]['position']);
    }

    public function testAnUnknownSlugIsA404(): void
    {
        $this->get('/exercises/nope');
        self::assertResponseStatusCodeSame(404);
        $this->get('/exercise-ladders/nope');
        self::assertResponseStatusCodeSame(404);
    }

    public function testItReadsTheLadders(): void
    {
        $all = $this->get('/exercise-ladders');
        self::assertResponseIsSuccessful();
        self::assertContains('vertical-pull', array_column($all['member'], 'slug'));

        $one = $this->get('/exercise-ladders/vertical-pull');
        self::assertSame('active-hang', $one['steps'][0]['exercise']);
        self::assertSame(1, $one['steps'][0]['position']);
    }
}
