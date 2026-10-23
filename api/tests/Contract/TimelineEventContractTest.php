<?php

declare(strict_types=1);

namespace App\Tests\Contract;

use Maggie\Finance\Service\ScenarioTimeline;
use PHPUnit\Framework\TestCase;

/**
 * The timeline events of a scenario (MAG-550): what the Gantt frise (MAG-554)
 * draws, on the phone, the tablet, the admin and in Maggie's answers. Written
 * from the builder's own constants.
 */
final class TimelineEventContractTest extends TestCase
{
    use ContractSnapshotTrait;

    public function testTheTimelineEventFormatIsPublished(): void
    {
        $this->assertMatchesContract(
            'timeline-events.json',
            [
                'fields' => ScenarioTimeline::FIELDS,
                'categories' => ScenarioTimeline::CATEGORIES,
                'types' => ScenarioTimeline::TYPES,
            ],
            'The format of the scenario timeline events changed. The frise of the apps and the answers of Maggie read these fields, categories and types.',
        );
    }

    public function testEveryTypeBelongsToAPublishedCategory(): void
    {
        foreach (ScenarioTimeline::TYPES as $type => $category) {
            self::assertContains($category, ScenarioTimeline::CATEGORIES, "{$type} is filed under an unpublished category.");
        }
    }
}
