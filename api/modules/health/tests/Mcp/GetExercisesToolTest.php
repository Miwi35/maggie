<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Health\Mcp\Tool\GetExercisesTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** get_exercises (MAG-774): the same filters as the endpoint. */
class GetExercisesToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Api/fixtures/daily_energy.yaml');
    }

    /**
     * @param list<string>|null $equipment
     *
     * @return array<string, mixed>
     */
    private function call(?string $pattern = null, ?array $equipment = null, ?string $slug = null): array
    {
        $tool = self::getContainer()->get(GetExercisesTool::class);
        \assert($tool instanceof GetExercisesTool);

        return json_decode($tool($pattern, $equipment, $slug), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testWithoutAUserItAnswersAnError(): void
    {
        self::assertArrayHasKey('error', $this->call());
    }

    public function testItFiltersByPatternAndEquipment(): void
    {
        $this->loginUser($this->getFixture('test_user'));

        $squats = $this->call(pattern: 'squat')['exercises'];
        self::assertNotEmpty($squats);
        self::assertSame(['squat'], array_values(array_unique(array_column($squats, 'pattern'))));

        $slugs = array_column($this->call(equipment: ['weighted_bag'])['exercises'], 'slug');
        self::assertContains('goblet-squat', $slugs);
        self::assertNotContains('pull-up', $slugs);
    }

    public function testItReturnsOneExerciseWithItsPosition(): void
    {
        $this->loginUser($this->getFixture('test_user'));

        $exercise = $this->call(slug: 'pull-up-negative')['exercises'][0];

        self::assertSame(4, $exercise['ladders'][0]['position']);
    }

    public function testUnknownInputsAnswerAnError(): void
    {
        $this->loginUser($this->getFixture('test_user'));

        self::assertArrayHasKey('error', $this->call(slug: 'nope'));
        self::assertArrayHasKey('error', $this->call(pattern: 'nope'));
        self::assertArrayHasKey('error', $this->call(equipment: ['barbell']));
    }
}
