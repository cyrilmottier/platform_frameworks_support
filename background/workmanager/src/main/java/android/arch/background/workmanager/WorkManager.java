/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.arch.background.workmanager;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.arch.background.workmanager.foreground.ForegroundProcessor;
import android.arch.background.workmanager.model.Dependency;
import android.arch.background.workmanager.systemjob.SystemJobConverter;
import android.arch.lifecycle.ProcessLifecycleOwner;
import android.content.Context;
import android.os.Build;
import android.support.annotation.RequiresApi;
import android.support.annotation.VisibleForTesting;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WorkManager is a class used to enqueue persisted work that is guaranteed to run after its
 * constraints are met.
 */
public final class WorkManager {

    private static final String TAG = "WorkManager";

    private Context mContext;
    private Processor mForegroundProcessor;
    private WorkDatabase mWorkDatabase;
    private ExecutorService mEnqueueExecutor = Executors.newSingleThreadExecutor();
    private WorkSpecConverter<JobInfo> mWorkSpecConverter;

    private static WorkManager sInstance = null;

    /**
     * Creates/Retrieves the static instance of {@link WorkManager}
     *
     * @param context {@link Context} used to create static instance
     * @return {@link WorkManager} object
     */
    public static synchronized WorkManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new WorkManager(context, false);
        }
        return sInstance;
    }

    @VisibleForTesting
    WorkManager(Context context, boolean useInMemoryDatabase) {
        mContext = context.getApplicationContext();
        mWorkDatabase = WorkDatabase.create(mContext, useInMemoryDatabase);

        mForegroundProcessor =
                new ForegroundProcessor(mContext, mWorkDatabase, ProcessLifecycleOwner.get());

        // TODO(janclarin): Wrap JobScheduler logic behind another interface.
        if (Build.VERSION.SDK_INT >= 21) {
            mWorkSpecConverter = new SystemJobConverter(mContext);
        }
    }

    @VisibleForTesting
    WorkDatabase getWorkDatabase() {
        return mWorkDatabase;
    }

    /**
     * Enqueues an item for background processing.
     *
     * @param work The {@link Work} to enqueue
     * @return A {@link WorkContinuation} that allows further chaining
     */
    public WorkContinuation enqueue(Work work) {
        return enqueue(work, null);
    }

    /**
     * Enqueues an item for background processing.
     *
     * @param workBuilder The {@link Work.Builder} to enqueue; internally {@code build} is called
     *                    on it
     * @return A {@link WorkContinuation} that allows further chaining
     */
    public WorkContinuation enqueue(Work.Builder workBuilder) {
        return enqueue(workBuilder.build(), null);
    }

    /**
     * Enqueues an item for background processing.
     *
     * @param workerClass The {@link Worker} to enqueue; this is a convenience method that makes a
     *                    {@link Work} object with default arguments using this Worker
     * @return A {@link WorkContinuation} that allows further chaining
     */
    public WorkContinuation enqueue(Class<? extends Worker> workerClass) {
        return enqueue(new Work.Builder(workerClass).build(), null);
    }

    WorkContinuation enqueue(Work work, String prerequisiteId) {
        WorkContinuation workContinuation = new WorkContinuation(this, work.getId());
        mEnqueueExecutor.execute(new EnqueueRunnable(work, prerequisiteId));
        return workContinuation;
    }

    /**
     * A Runnable to enqueue a {@link Work} in the database.
     */
    private class EnqueueRunnable implements Runnable {
        private Work mWork;
        private String mPrerequisiteId;

        EnqueueRunnable(Work work, String prerequisiteId) {
            mWork = work;
            mPrerequisiteId = prerequisiteId;
        }

        @Override
        public void run() {
            mWorkDatabase.beginTransaction();
            try {
                mWorkDatabase.workSpecDao().insertWorkSpec(mWork.getWorkSpec());
                if (mPrerequisiteId != null) {
                    Dependency dep = new Dependency(mWork.getId(), mPrerequisiteId);
                    mWorkDatabase.dependencyDao().insertDependency(dep);
                } else {
                    mForegroundProcessor.process(mWork.getId());

                    if (Build.VERSION.SDK_INT >= 21) {
                        scheduleWorkWithJobScheduler();
                        // TODO(janclarin): Schedule dependent work.
                    }
                }

                mWorkDatabase.setTransactionSuccessful();
            } finally {
                mWorkDatabase.endTransaction();
            }
        }

        @RequiresApi(api = 21)
        private void scheduleWorkWithJobScheduler() {
            JobScheduler jobScheduler =
                    (JobScheduler) mContext.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (jobScheduler != null) {
                JobInfo jobInfo = mWorkSpecConverter.convert(mWork.getWorkSpec());
                jobScheduler.schedule(jobInfo);
            }
        }
    }
}

