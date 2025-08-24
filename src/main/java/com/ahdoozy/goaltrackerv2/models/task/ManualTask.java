package com.ahdoozy.goaltrackerv2.models.task;

import com.ahdoozy.goaltrackerv2.models.enums.Status;
import com.ahdoozy.goaltrackerv2.models.enums.TaskType;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Setter
@Getter
@SuperBuilder
/**
 * Task representing a manually tracked entry (checklist-style).
 * Toggled complete/incomplete by the user.
 */
public final class ManualTask extends Task
{
    private String description;

    public void toggle()
    {
        this.setStatus(this.getStatus().isNotStarted() ? Status.COMPLETED : Status.NOT_STARTED);
    }

    @Override
    public String toString()
    {
        return description;
    }

    @Override
    public String getDisplayName()
    {
        return description;
    }

    @Override
    public TaskType getType()
    {
        return TaskType.MANUAL;
    }
}
