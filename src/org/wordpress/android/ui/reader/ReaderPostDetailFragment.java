package org.wordpress.android.ui.reader;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.webkit.WebView.HitTestResult;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.actionbarsherlock.app.SherlockFragment;

import org.wordpress.android.Constants;
import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.datasets.ReaderCommentTable;
import org.wordpress.android.datasets.ReaderLikeTable;
import org.wordpress.android.datasets.ReaderPostTable;
import org.wordpress.android.datasets.ReaderThumbnailTable;
import org.wordpress.android.datasets.ReaderUserTable;
import org.wordpress.android.models.ReaderComment;
import org.wordpress.android.models.ReaderPost;
import org.wordpress.android.models.ReaderUrlList;
import org.wordpress.android.ui.reader.ReaderActivityLauncher.OpenUrlType;
import org.wordpress.android.ui.reader.actions.ReaderActions;
import org.wordpress.android.ui.reader.actions.ReaderCommentActions;
import org.wordpress.android.ui.reader.actions.ReaderPostActions;
import org.wordpress.android.ui.reader.adapters.ReaderCommentAdapter;
import org.wordpress.android.util.AniUtils;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.AppLog.T;
import org.wordpress.android.util.DateTimeUtils;
import org.wordpress.android.util.DisplayUtils;
import org.wordpress.android.util.EditTextUtils;
import org.wordpress.android.util.HtmlUtils;
import org.wordpress.android.util.PhotonUtils;
import org.wordpress.android.util.StringUtils;
import org.wordpress.android.util.SysUtils;
import org.wordpress.android.util.ToastUtils;
import org.wordpress.android.util.UrlUtils;
import org.wordpress.android.widgets.WPNetworkImageView;

import java.util.ArrayList;

/**
 * Created by nbradbury on 7/8/13.
 */
public class ReaderPostDetailFragment extends SherlockFragment {
    protected static final String ARG_BLOG_ID = "blog_id";
    protected static final String ARG_POST_ID = "post_id";
    protected static final String ARG_BLOG_FOLLOW_STATUS_CHANGED = "blog_follow_status_changed";
    private static final String ARG_LIST_STATE = "list_state";

    private long mPostId;
    private long mBlogId;
    private ReaderPost mPost;

    private ViewGroup mLayoutIcons;
    private ViewGroup mLayoutLikes;
    private ListView mListView;
    private ViewGroup mCommentFooter;
    private ProgressBar mProgressFooter;
    private WebView mWebView;

    private boolean mIsAddCommentBoxShowing = false;
    private long mReplyToCommentId = 0;
    private boolean mHasAlreadyUpdatedPost = false;
    private boolean mIsUpdatingComments = false;
    private boolean mIsPostChanged = false;
    private boolean mIsBlogFollowStatusChanged = false;

    private Parcelable mListState = null;

    private final ReaderUrlList mVideoThumbnailUrls = new ReaderUrlList();
    private final Handler mHandler = new Handler();

    private boolean mIsMoving;
    private float mLastMotionY;

    private ReaderFullScreenUtils.FullScreenListener mFullScreenListener;

    private static final int MOVE_MIN_DIFF = 8;
    private static final long WEBVIEW_DELAY_MS = 1000L;

    private ListView getListView() {
        return mListView;
    }

    private void setupListView(ListView listView) {
        /*
         * if full-screen mode is supported, enable full-screen when user scrolls down
         * and disable full-screen when user scrolls up
         */
        if (isFullScreenSupported()) {
            listView.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    int action = event.getAction() & MotionEvent.ACTION_MASK;
                    final float y = event.getY();
                    final int yDiff = (int) (y - mLastMotionY);
                    final boolean isFullScreen = isFullScreen();
                    mLastMotionY = y;

                    switch (action) {
                        case MotionEvent.ACTION_MOVE :
                            if (mIsMoving) {
                                if (mIsAddCommentBoxShowing) {
                                    // user is typing a comment, so don't toggle full-screen
                                    return false;
                                } else if (yDiff < -MOVE_MIN_DIFF && !isFullScreen && ReaderFullScreenUtils.canScrollDown(mListView)) {
                                    // user is scrolling down, so enable full-screen
                                    setIsFullScreen(true);
                                    return true;
                                } else if (isFullScreen && !ReaderFullScreenUtils.canScrollUp(mListView)) {
                                    // disable full-screen if user scrolls to the top
                                    setIsFullScreen(false);
                                } else if (isFullScreen && !ReaderFullScreenUtils.canScrollDown(mListView)) {
                                    // disable full-screen if user scrolls to the bottom
                                    setIsFullScreen(false);
                                } else if (yDiff > MOVE_MIN_DIFF && isFullScreen) {
                                    // user is scrolling up, so disable full-screen
                                    setIsFullScreen(false);
                                    return true;
                                }
                            } else {
                                mIsMoving = true;
                            }
                            break;

                        default :
                            mIsMoving = false;
                            break;
                    }

                    return false;
                }
            });
        }
    }

    /*
     * adapter containing comments for this post
     */
    private ReaderCommentAdapter mAdapter;
    private ReaderCommentAdapter getCommentAdapter() {
        if (mAdapter == null) {
            if (mPost == null)
                AppLog.w(T.READER, "comment adapter created before post loaded");

            ReaderActions.DataLoadedListener dataLoadedListener = new ReaderActions.DataLoadedListener() {
                @Override
                public void onDataLoaded(boolean isEmpty) {
                    if (!hasActivity())
                        return;
                    // show footer below comments when comments exist
                    mCommentFooter.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
                    // restore listView state (scroll position) if it was saved during rotation
                    if (mListState != null) {
                        if (!isEmpty)
                            getListView().onRestoreInstanceState(mListState);
                        mListState = null;
                    }
                }
            };

            // adapter calls this when user taps reply icon
            ReaderCommentAdapter.RequestReplyListener replyListener = new ReaderCommentAdapter.RequestReplyListener() {
                @Override
                public void onRequestReply(long commentId) {
                    if (!mIsAddCommentBoxShowing) {
                        showAddCommentBox(commentId);
                    } else {
                        hideAddCommentBox();
                    }
                }
            };

            // adapter uses this to request more comments from server when it reaches the end and
            // detects that more comments exist on the server than are stored locally
            ReaderActions.DataRequestedListener dataRequestedListener = new ReaderActions.DataRequestedListener() {
                @Override
                public void onRequestData(ReaderActions.RequestDataAction action) {
                    if (mIsUpdatingComments)
                        return;
                    AppLog.i(T.READER, "requesting newer comments");
                    updateComments();
                }
            };
            mAdapter = new ReaderCommentAdapter(getActivity(), mPost, replyListener, dataLoadedListener, dataRequestedListener);
        }
        return mAdapter;
    }

    private boolean hasCommentAdapter() {
        return mAdapter!=null;
    }

    private boolean isCommentAdapterEmpty() {
        return (mAdapter==null || mAdapter.isEmpty());
    }




    protected static ReaderPostDetailFragment newInstance(long blogId, long postId) {
        AppLog.d(T.READER, "post detail fragment newInstance");

        Bundle args = new Bundle();
        args.putLong(ARG_BLOG_ID, blogId);
        args.putLong(ARG_POST_ID, postId);

        ReaderPostDetailFragment fragment = new ReaderPostDetailFragment();
        fragment.setArguments(args);

        return fragment;
    }

    @Override
    public void setArguments(Bundle args) {
        super.setArguments(args);
        if (args != null) {
            mBlogId = args.getLong(ARG_BLOG_ID);
            mPostId = args.getLong(ARG_POST_ID);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.reader_activity_post_detail, container, false);

        // locate & init listView, hide it until post is loaded
        mListView = (ListView) view.findViewById(android.R.id.list);
        setupListView(mListView);
        mListView.setVisibility(View.INVISIBLE);

        // add a header to the listView that's the same height as the ActionBar when fullscreen
        // mode is supported
        if (isFullScreenSupported()) {
            ReaderFullScreenUtils.addListViewHeader(container.getContext(), mListView);
        }

        // add post detail as header to listView - must be done before setting adapter
        ViewGroup headerDetail = (ViewGroup) inflater.inflate(R.layout.reader_listitem_post_detail, mListView, false);
        mListView.addHeaderView(headerDetail, null, false);

        // add listView footer containing progress bar - footer appears whenever there are comments,
        // progress bar appears when loading new comments
        mCommentFooter = (ViewGroup) inflater.inflate(R.layout.reader_footer_progress, mListView, false);
        mCommentFooter.setVisibility(View.GONE);
        mCommentFooter.setBackgroundColor(getResources().getColor(R.color.grey_extra_light));
        mProgressFooter = (ProgressBar) mCommentFooter.findViewById(R.id.progress_footer);
        mProgressFooter.setVisibility(View.INVISIBLE);
        mListView.addFooterView(mCommentFooter);

        mLayoutIcons = (ViewGroup) view.findViewById(R.id.layout_actions);
        mLayoutLikes = (ViewGroup) view.findViewById(R.id.layout_likes);

        // setup the webView - note that JavaScript is disabled since it's a security risk
        // http://developer.android.com/training/articles/security-tips.html#WebView
        mWebView = (WebView) view.findViewById(R.id.webView);
        mWebView.getSettings().setJavaScriptEnabled(false);
        mWebView.getSettings().setUserAgentString(Constants.USER_AGENT);
        mWebView.setVisibility(View.INVISIBLE);

        // detect image taps so we can open images in the photo viewer activity
        mWebView.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction()==MotionEvent.ACTION_UP) {
                    HitTestResult hr = ((WebView)v).getHitTestResult();
                    if (hr!=null && (hr.getType()==HitTestResult.IMAGE_TYPE || hr.getType()==HitTestResult.SRC_IMAGE_ANCHOR_TYPE)) {
                        String imageUrl = hr.getExtra();
                        if (imageUrl==null)
                            return false;
                        // skip if image is a file: reference - this will be the video overlay, ie:
                        // file:///android_res/drawable/ic_reader_video_overlay.png
                        if (imageUrl.startsWith("file:"))
                            return false;
                        // skip if image is a video thumbnail (see processVideos)
                        if (mVideoThumbnailUrls.contains(imageUrl))
                            return false;
                        // skip if image is a VideoPress thumbnail (anchor around thumbnail will
                        // take user to actual video - see ReaderPost.cleanupVideoPress)
                        if (imageUrl.contains("videos.files."))
                            return false;
                        showPhotoViewer(imageUrl);
                        return true;
                    }
                }
                return false;
            }
        });

        return view;
    }

    private boolean hasPost() {
        return (mPost != null);
    }

    protected void reloadPost() {
        if (!hasPost() || !hasActivity())
            return;
        showPost();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (!hasPost() && !mIsPostTaskRunning)
            showPost();
    }

    @Override
    public void onCreateOptionsMenu(com.actionbarsherlock.view.Menu menu, com.actionbarsherlock.view.MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        menu.clear();
        inflater.inflate(R.menu.reader_native_detail, menu);
    }

    @Override
    public boolean onOptionsItemSelected(com.actionbarsherlock.view.MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_browse :
                if (hasPost())
                    ReaderActivityLauncher.openUrl(getActivity(), mPost.getUrl(), OpenUrlType.EXTERNAL);
                return true;
            case R.id.menu_share :
                sharePage();
                return true;
            default :
                return super.onOptionsItemSelected(item);
        }
    }

    private boolean hasActivity() {
        return (getActivity() != null && !isRemoving());
    }

    /*
     * full-screen mode hides the ActionBar and icon bar
     */
    private boolean isFullScreen() {
        return (mFullScreenListener != null && mFullScreenListener.isFullScreen());
    }

    private boolean isFullScreenSupported() {
        return (mFullScreenListener != null && mFullScreenListener.isFullScreenSupported());
    }

    private void setIsFullScreen(boolean enableFullScreen) {
        if (mFullScreenListener == null)
            return;
        if (!mFullScreenListener.onRequestFullScreen(enableFullScreen))
            return;
        if (mPost.isWP())
            animateIconBar(!enableFullScreen);
    }

    /*
     * animate in/out the layout containing the reblog/comment/like icons
     */
    private void animateIconBar(boolean isAnimatingIn) {
        if (isAnimatingIn && mLayoutIcons.getVisibility() == View.VISIBLE)
            return;
        if (!isAnimatingIn && mLayoutIcons.getVisibility() != View.VISIBLE)
            return;

        final Animation animation;
        if (isAnimatingIn) {
            animation = new TranslateAnimation(Animation.RELATIVE_TO_SELF, 0.0f,
                    Animation.RELATIVE_TO_SELF, 0.0f, Animation.RELATIVE_TO_SELF,
                    1.0f, Animation.RELATIVE_TO_SELF, 0.0f);
        } else {
            animation = new TranslateAnimation(Animation.RELATIVE_TO_SELF, 0.0f,
                    Animation.RELATIVE_TO_SELF, 0.0f, Animation.RELATIVE_TO_SELF,
                    0.0f, Animation.RELATIVE_TO_SELF, 1.0f);
        }

        animation.setDuration(getResources().getInteger(android.R.integer.config_mediumAnimTime));

        mLayoutIcons.clearAnimation();
        mLayoutIcons.startAnimation(animation);
        mLayoutIcons.setVisibility(isAnimatingIn ? View.VISIBLE : View.GONE);
    }

    private static final String KEY_SHOW_COMMENT_BOX = "show_comment_box";
    private static final String KEY_REPLY_TO_COMMENT_ID = "reply_to_comment_id";
    private static final String KEY_ALREADY_UPDATED = "already_updated";
    private static final String KEY_IS_POST_CHANGED = "is_post_changed";
    private static final String KEY_IS_BLOG_FOLLOW_STATUS_CHANGED = "is_blog_follow_status_changed";
    
    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putLong(ARG_BLOG_ID, mBlogId);
        outState.putLong(ARG_POST_ID, mPostId);
        outState.putBoolean(KEY_ALREADY_UPDATED, mHasAlreadyUpdatedPost);
        outState.putBoolean(KEY_IS_POST_CHANGED, mIsPostChanged);
        outState.putBoolean(KEY_SHOW_COMMENT_BOX, mIsAddCommentBoxShowing);
        outState.putBoolean(KEY_IS_BLOG_FOLLOW_STATUS_CHANGED, mIsBlogFollowStatusChanged);
        if (mIsAddCommentBoxShowing)
            outState.putLong(KEY_REPLY_TO_COMMENT_ID, mReplyToCommentId);

        // retain listView state if a comment has been scrolled to - this enables us to restore
        // the scroll position after comment data is loaded
        if (getListView().getFirstVisiblePosition() > 0) {
            mListState = getListView().onSaveInstanceState();
            outState.putParcelable(ARG_LIST_STATE, mListState);
        }
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        setHasOptionsMenu(true);

        if (savedInstanceState != null) {
            mBlogId = savedInstanceState.getLong(ARG_BLOG_ID);
            mPostId = savedInstanceState.getLong(ARG_POST_ID);
            mHasAlreadyUpdatedPost = savedInstanceState.getBoolean(KEY_ALREADY_UPDATED);
            mIsPostChanged = savedInstanceState.getBoolean(KEY_IS_POST_CHANGED);
            mIsBlogFollowStatusChanged = savedInstanceState.getBoolean(KEY_IS_BLOG_FOLLOW_STATUS_CHANGED);
            if (savedInstanceState.getBoolean(KEY_SHOW_COMMENT_BOX)) {
                long replyToCommentId = savedInstanceState.getLong(KEY_REPLY_TO_COMMENT_ID);
                showAddCommentBox(replyToCommentId);
            }
            mListState = savedInstanceState.getParcelable(ARG_LIST_STATE);
        }
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        // clear title until post is loaded
        activity.setTitle(null);
        if (activity instanceof ReaderFullScreenUtils.FullScreenListener)
            mFullScreenListener = (ReaderFullScreenUtils.FullScreenListener) activity;
    }

    // TODO: post list needs to know when the post has been changed
    /*@Override
    public void onBackPressed() {
        // if comment box is showing, cancel it rather than backing out of this activity
        if (mIsAddCommentBoxShowing) {
            hideAddCommentBox();
        } else {
            // return blogId/postId to caller, but only set to RESULT_OK if the post has changed
            // so the calling activity can refresh the displayed post
            Intent data = new Intent();
            data.putExtra(ARG_BLOG_ID, mBlogId);
            data.putExtra(ARG_POST_ID, mPostId);
            data.putExtra(ARG_BLOG_FOLLOW_STATUS_CHANGED, mIsBlogFollowStatusChanged);
            
            if (mIsPostChanged) {
                setResult(RESULT_OK, data);
            } else {
                setResult(RESULT_CANCELED, data);
            }
            super.onBackPressed();
        }
    }*/

    /*
     * triggered when user chooses to like or follow - actionView is the ImageView or TextView
     * associated with the action (ex: like button)
     */
    private void doPostAction(View actionView, ReaderPostActions.PostAction action, ReaderPost post) {
        boolean isSelected = actionView.isSelected();
        actionView.setSelected(!isSelected);
        AniUtils.zoomAction(actionView);

        if (!ReaderPostActions.performPostAction(action, post, null)) {
            actionView.setSelected(isSelected);
            return;
        }

        // get the post again, since it has changed
        mPost = ReaderPostTable.getPost(mBlogId, mPostId);
        mIsPostChanged = true;

        // call returns before api completes, but local version of post will have been changed
        // so refresh to show those changes
        switch (action) {
            case TOGGLE_LIKE:
                refreshLikes(true);
                break;
            case TOGGLE_FOLLOW:
                mIsBlogFollowStatusChanged = true;
                refreshFollowed();
                break;
        }
    }

    /*
     * triggered when user chooses to reblog the post
     */
    private void doPostReblog(ImageView imgBtnReblog, ReaderPost post) {
        if (!hasActivity())
            return;

        if (post.isRebloggedByCurrentUser) {
            ToastUtils.showToast(getActivity(), R.string.reader_toast_err_already_reblogged);
            return;
        }

        imgBtnReblog.setSelected(true);
        AniUtils.zoomAction(imgBtnReblog);
        ReaderActivityLauncher.showReaderReblogForResult(getActivity(), post);
    }

    /*
     * display the standard Android share chooser to share a link to this post
     */
    private void sharePage() {
        if (!hasActivity())
            return;
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, mPost.getUrl());
        intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.reader_share_subject, getString(R.string.app_name)));
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.reader_share_link)));
        } catch (android.content.ActivityNotFoundException ex) {
            ToastUtils.showToast(getActivity(), R.string.reader_toast_err_share_intent);
        }
    }

    /*
     * get the latest version of this post - used to get latest like/comment counts
     */
    private void updatePost() {
        if (!hasPost() || !mPost.isWP())
            return;

        final int origNumLikes = mPost.numLikes;
        final int origNumReplies = mPost.numReplies;

        ReaderActions.UpdateResultListener resultListener = new ReaderActions.UpdateResultListener() {
            @Override
            public void onUpdateResult(ReaderActions.UpdateResult result) {
                switch (result) {
                    case CHANGED :
                        // post has changed, so get latest version
                        mPost = ReaderPostTable.getPost(mBlogId, mPostId);
                        mIsPostChanged = true;
                        break;
                    case FAILED:
                        // failed to get post, so do nothing here
                        return;
                    default :
                        // unchanged
                        break;
                }

                // updating the post will get its liking users, so refresh likes here if any exist
                // to make sure the correct liking avatars are shown
                if (origNumLikes > 0 || mPost.numLikes > 0)
                    refreshLikes(false);

                // determine whether we need to make a separate request to update comments
                new Thread() {
                    @Override
                    public void run() {
                        final boolean isCommentsChanged = (mPost.numReplies != origNumReplies
                                                        || mPost.numReplies != ReaderCommentTable.getNumCommentsForPost(mPost));
                        if (isCommentsChanged) {
                            mHandler.post(new Runnable() {
                                public void run() {
                                    updateComments();
                                }
                            });
                        }
                    }
                }.start();
            }
        };
        ReaderPostActions.updatePost(mPost, resultListener);
    }

    /*
     * request comments for this post
     */
    private void updateComments() {
        if (!hasPost() || !mPost.isWP())
            return;
        if (mIsUpdatingComments)
            return;

        mIsUpdatingComments = true;

        if (!isCommentAdapterEmpty())
            showProgressFooter();

        ReaderActions.UpdateResultListener resultListener = new ReaderActions.UpdateResultListener() {
            @Override
            public void onUpdateResult(ReaderActions.UpdateResult result) {
                mIsUpdatingComments = false;
                if (!hasActivity())
                    return;
                hideProgressFooter();
                if (result == ReaderActions.UpdateResult.CHANGED) {
                    mIsPostChanged = true;
                    refreshComments();
                }
            }
        };
        ReaderCommentActions.updateCommentsForPost(mPost, resultListener);
    }

    /*
     * show progress bar at the bottom of the screen - used when getting newer comments
     */
    private void showProgressFooter() {
        if (mProgressFooter == null || mProgressFooter.getVisibility() == View.VISIBLE )
            return;
        mProgressFooter.setVisibility(View.VISIBLE);
    }

    /*
     * hide the footer progress bar if it's showing
     */
    private void hideProgressFooter() {
        if (mProgressFooter == null || mProgressFooter.getVisibility() != View.VISIBLE )
            return;
        mProgressFooter.setVisibility(View.INVISIBLE);
    }

    /*
     * refresh adapter so latest comments appear
     */
    private void refreshComments() {
        getCommentAdapter().refreshComments();
    }

    /*
     * returns the top-level parent view for the post detail views
     */
    private ViewGroup getContainerView() {
        return (ViewGroup) getActivity().findViewById(R.id.layout_post_detail_container);
    }

    /*
     * show latest likes for this post - pass true to force reloading avatars (used when user clicks
     * the like button, to ensure the current user's avatar appears)
     */
    private void refreshLikes(final boolean forceReload) {
        if (!hasActivity())
            return;
        if (!hasPost() || !mPost.isWP())
            return;

        new Thread() {
            @Override
            public void run() {
                final ViewGroup container = getContainerView();
                final ImageView imgBtnLike = (ImageView) container.findViewById(R.id.image_like_btn);
                final ViewGroup layoutLikingAvatars = (ViewGroup) mLayoutLikes.findViewById(R.id.layout_liking_avatars);
                final TextView txtLikeCount = (TextView) mLayoutLikes.findViewById(R.id.text_like_count);

                final int marginExtraSmall = getResources().getDimensionPixelSize(R.dimen.margin_extra_small);
                final int marginLarge = getResources().getDimensionPixelSize(R.dimen.margin_large);
                final int likeAvatarSize = getResources().getDimensionPixelSize(R.dimen.avatar_sz_small);
                final int likeAvatarSizeWithMargin = likeAvatarSize + (marginExtraSmall * 2);

                // determine how many avatars will fit the space
                final int displayWidth = DisplayUtils.getDisplayPixelWidth(getActivity());
                final int spaceForAvatars = displayWidth - (marginLarge * 2);
                final int maxAvatars = spaceForAvatars / likeAvatarSizeWithMargin;

                // get avatars of liking users up to the max
                final ArrayList<String> avatars = ReaderUserTable.getAvatarUrls(ReaderLikeTable.getLikesForPost(mPost), maxAvatars);

                mHandler.post(new Runnable() {
                    public void run() {
                        if (!hasActivity())
                            return;

                        imgBtnLike.setSelected(mPost.isLikedByCurrentUser);
                        imgBtnLike.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                doPostAction(imgBtnLike, ReaderPostActions.PostAction.TOGGLE_LIKE, mPost);
                            }
                        });

                        // nothing more to do if no likes or liking avatars haven't been retrieved yet
                        if (avatars.size()==0 || mPost.numLikes==0) {
                            mLayoutLikes.setVisibility(View.GONE);
                            return;
                        }

                        // set the like count text
                        if (mPost.isLikedByCurrentUser) {
                            if (mPost.numLikes==1) {
                                txtLikeCount.setText(R.string.reader_likes_only_you);
                            } else {
                                txtLikeCount.setText(mPost.numLikes==2 ? getString(R.string.reader_likes_you_and_one) : getString(R.string.reader_likes_you_and_multi, mPost.numLikes-1));
                            }
                        } else {
                            txtLikeCount.setText(mPost.numLikes==1 ? getString(R.string.reader_likes_one) : getString(R.string.reader_likes_multi, mPost.numLikes));
                        }

                        // now show the liking avatars - clicking likes view shows activity displaying all liking users
                        View.OnClickListener clickListener = new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                ReaderActivityLauncher.showReaderLikingUsers(getActivity(), mPost);
                            }
                        };
                        mLayoutLikes.setOnClickListener(clickListener);

                        // skip adding liking avatars if the view's child count indicates that we've already
                        // added the max on a previous call to this routine
                        if (forceReload || layoutLikingAvatars.getChildCount() < maxAvatars) {
                            layoutLikingAvatars.removeAllViews();
                            LayoutInflater inflater = getActivity().getLayoutInflater();
                            for (String url: avatars) {
                                WPNetworkImageView imgAvatar = (WPNetworkImageView) inflater.inflate(R.layout.reader_like_avatar, layoutLikingAvatars, false);
                                layoutLikingAvatars.addView(imgAvatar);
                                imgAvatar.setImageUrl(PhotonUtils.fixAvatar(url, likeAvatarSize), WPNetworkImageView.ImageType.AVATAR);
                            }
                        }

                        // show the liking layout if it's not already showing
                        if (mLayoutLikes.getVisibility() != View.VISIBLE) {
                            // if the webView hasn't been made visible yet (ie: it hasn't loaded),
                            // delay the appearance of the likes view (otherwise it may appear before
                            // the webView content appears, causing it to be pushed down once the
                            // content loads)
                            if (mWebView.getVisibility() == View.VISIBLE) {
                                mLayoutLikes.setVisibility(View.VISIBLE);
                            } else {
                                new Handler().postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        mLayoutLikes.setVisibility(View.VISIBLE);
                                    }
                                }, WEBVIEW_DELAY_MS);
                            }
                        }
                    }
                });
            }
        }.start();
    }


    /*
     * show the view enabling adding a comment - triggered when user hits comment icon/count in header
     * note that this view is hidden at design time, so it will be shown the first time user taps icon.
     * pass 0 for the replyToCommentId to add a parent-level comment to the post, or pass a real
     * comment id to reply to a specific comment
     */
    private void showAddCommentBox(final long replyToCommentId) {
        if (!hasActivity())
            return;

        // skip if it's already showing
        if (mIsAddCommentBoxShowing)
            return;

        // don't show comment box if a comment is currently being submitted
        if (mIsSubmittingComment)
            return;

        final ViewGroup container = getContainerView();
        final ViewGroup layoutCommentBox = (ViewGroup) container.findViewById(R.id.layout_comment_box);
        final EditText editComment = (EditText) layoutCommentBox.findViewById(R.id.edit_comment);
        final ImageView imgBtnComment = (ImageView) container.findViewById(R.id.image_comment_btn);

        // disable full-screen when comment box is showing
        if (isFullScreen())
            setIsFullScreen(false);

        // different hint depending on whether user is replying to a comment or commenting on the post
        editComment.setHint(replyToCommentId==0 ? R.string.reader_hint_comment_on_post : R.string.reader_hint_comment_on_comment);

        imgBtnComment.setSelected(true);
        AniUtils.flyIn(layoutCommentBox);

        editComment.requestFocus();
        editComment.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId==EditorInfo.IME_ACTION_DONE || actionId==EditorInfo.IME_ACTION_SEND)
                    submitComment(replyToCommentId);
                return false;
            }
        });

        // submit comment when image tapped
        final ImageView imgPostComment = (ImageView) container.findViewById(R.id.image_post_comment);
        imgPostComment.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submitComment(replyToCommentId);
            }
        });

        InputMethodManager imm = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.showSoftInput(editComment, InputMethodManager.SHOW_IMPLICIT);

        // if user is replying to another comment, highlight the comment being replied to and scroll
        // it to the top so the user can see which comment they're replying to - note that scrolling
        // is delayed to give time for listView to reposition due to soft keyboard appearing
        if (replyToCommentId!=0) {
            getCommentAdapter().setHighlightCommentId(replyToCommentId, false);
            getListView().postDelayed(new Runnable() {
                @Override
                public void run() {
                    scrollToCommentId(replyToCommentId);
                }
            }, 300);
        }

        // mReplyToCommentId must be saved here so it can be stored by onSaveInstanceState()
        mReplyToCommentId = replyToCommentId;
        mIsAddCommentBoxShowing = true;
    }

    private void hideAddCommentBox() {
        if (!hasActivity())
            return;
        if (!mIsAddCommentBoxShowing)
            return;

        final ViewGroup container = getContainerView();
        final ViewGroup layoutCommentBox = (ViewGroup) container.findViewById(R.id.layout_comment_box);
        final EditText editComment = (EditText) layoutCommentBox.findViewById(R.id.edit_comment);
        final ImageView imgBtnComment = (ImageView) container.findViewById(R.id.image_comment_btn);

        imgBtnComment.setSelected(false);
        AniUtils.flyOut(layoutCommentBox);
        EditTextUtils.hideSoftInput(editComment);

        getCommentAdapter().setHighlightCommentId(0, false);

        mIsAddCommentBoxShowing = false;
        mReplyToCommentId = 0;
    }

    private void toggleShowAddCommentBox() {
        if (mIsAddCommentBoxShowing) {
            hideAddCommentBox();
        } else {
            showAddCommentBox(0);
        }
    }

    /*
     * scrolls the passed comment to the top of the listView
     */
    private void scrollToCommentId(long commentId) {
        int position = getCommentAdapter().indexOfCommentId(commentId);
        if (position == -1)
            return;
        getListView().setSelectionFromTop(position + getListView().getHeaderViewsCount(), 0);
    }

    /*
     * post the text typed into the comment box as a comment on the current post
     */
    private boolean mIsSubmittingComment = false;
    private void submitComment(final long replyToCommentId) {
        final ViewGroup container = getContainerView();
        final EditText editComment = (EditText) container.findViewById(R.id.edit_comment);
        final String commentText = EditTextUtils.getText(editComment);
        if (TextUtils.isEmpty(commentText))
            return;

        // hide the comment box - this provides immediate indication that comment is being posted
        // and prevents users from submitting the same comment twice
        hideAddCommentBox();

        // generate a "fake" comment id to assign to the new comment so we can add it to the db
        // and reflect it in the adapter before the API call returns
        final long fakeCommentId = ReaderCommentActions.generateFakeCommentId();

        mIsSubmittingComment = true;
        ReaderActions.CommentActionListener actionListener = new ReaderActions.CommentActionListener() {
            @Override
            public void onActionResult(boolean succeeded, ReaderComment newComment) {
                mIsSubmittingComment = false;
                if (!hasActivity())
                    return;
                if (succeeded) {
                    // comment posted successfully so stop highlighting the fake one and replace
                    // it with the real one
                    getCommentAdapter().setHighlightCommentId(0, false);
                    getCommentAdapter().replaceComment(fakeCommentId, newComment);
                    getListView().invalidateViews();
                } else {
                    // comment failed to post - show the comment box again with the comment text intact,
                    // and remove the "fake" comment from the adapter
                    editComment.setText(commentText);
                    showAddCommentBox(replyToCommentId);
                    getCommentAdapter().removeComment(fakeCommentId);
                    ToastUtils.showToast(getActivity(), R.string.reader_toast_err_comment_failed, ToastUtils.Duration.LONG);
                }
            }
        };

        final ReaderComment newComment = ReaderCommentActions.submitPostComment(mPost,
                                                                                fakeCommentId,
                                                                                commentText,
                                                                                replyToCommentId,
                                                                                actionListener);
        if (newComment!=null) {
            mIsPostChanged = true;
            editComment.setText(null);
            // add the "fake" comment to the adapter, highlight it, and show a progress bar
            // next to it while it's submitted
            getCommentAdapter().setHighlightCommentId(newComment.commentId, true);
            getCommentAdapter().addComment(newComment);
            // make sure it's scrolled into view
            scrollToCommentId(fakeCommentId);
        }
    }

    /*
     * refresh the follow button based on whether this is a followed blog
     */
    private void refreshFollowed() {
        final TextView txtFollow = (TextView) getContainerView().findViewById(R.id.text_follow);
        final boolean isFollowed = ReaderPostTable.isPostFollowed(mPost);
        showFollowedStatus(txtFollow, isFollowed);
    }

    private void showFollowedStatus(final TextView txtFollow, boolean isFollowed) {
        final String followText = (isFollowed ? getString(R.string.reader_btn_unfollow) : getString(R.string.reader_btn_follow)).toUpperCase();
        txtFollow.setText(followText);
        int drawableId = (isFollowed ? R.drawable.note_icon_following : R.drawable.note_icon_follow);
        txtFollow.setCompoundDrawablesWithIntrinsicBounds(drawableId, 0, 0, 0);
        txtFollow.setSelected(isFollowed);
        txtFollow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                doPostAction(txtFollow, ReaderPostActions.PostAction.TOGGLE_FOLLOW, mPost);
            }
        });
    }

    private static String getAttribute(final String html, final String attribute, int tagStart, int tagEnd) {
        // hack to determine whether html uses single-quoted attributes
        boolean usesSingleQuotes = html.contains(attribute + "='");

        final String searchFor = attribute + (usesSingleQuotes ? "='" : "=\"");
        int attrStart = html.indexOf(searchFor);
        if (attrStart == -1 || attrStart > tagEnd)
            return null;

        // find the end of the attribute's value
        int attrEnd = html.indexOf(usesSingleQuotes ? "'" : "\"", attrStart + searchFor.length());
        if (attrEnd == -1 || attrEnd > tagEnd)
            return null;

        // extract the attribute's value
        return html.substring(attrStart + searchFor.length(), attrEnd);
    }

    /*
     * extracts the src from iframes and inserts a video player image with a link to the actual video
     * this is necessary since embedded videos won't work (and the CSS we're using hides iframes)
     * ex: <iframe src='http://player.vimeo.com/video/36281008' width='500' height='281' frameborder='0'></iframe>
     * this isn't very performance-friendly due to all the string creation, but it does the job
     * and performance isn't critical since this is only called once (from inside the AsyncTask)
     */
    private static final String OVERLAY_IMG = "file:///android_res/drawable/ic_reader_video_overlay.png";
    private String processVideos(String text) {
        if (text==null)
            return "";

        int iFrameStart = text.indexOf("<iframe");
        if (iFrameStart == -1)
            return text;

        while (iFrameStart > -1) {
            int iFrameEnd = text.indexOf(">", iFrameStart);
            if (iFrameEnd == -1)
                return text;

            // extract the src attribute
            String src = getAttribute(text, "src", iFrameStart, iFrameEnd);
            if (src == null)
                return text;

            boolean isVideo = (src.contains("youtube")
                            || src.contains("video")
                            || src.contains("vimeo"));

            final String videoDiv;
            if (isVideo) {
                // use generic video player overlay if we don't have the thumbnail for this video, otherwise
                // show the thumbnail with the player overlay on top of it - note there's a good chance we
                // have the thumbnail since it was likely already downloaded by the post list
                String thumbnailUrl = ReaderThumbnailTable.getThumbnailUrl(src);
                videoDiv = makeVideoDiv(src, thumbnailUrl);
                // keep track of thumbnail urls so we know when they're clicked
                if (!TextUtils.isEmpty(thumbnailUrl))
                    mVideoThumbnailUrls.add(thumbnailUrl);
                // insert the video div before the iframe - note that the iframe will be hidden by the CSS used
                // in the AsyncTask below
                text = text.substring(0, iFrameStart) + videoDiv + text.substring(iFrameStart);
            } else {
                // if we get here it means we're not sure the iframe is a video, in which case don't show anything
                videoDiv = "";
            }

            iFrameStart = text.indexOf("<iframe", iFrameEnd + videoDiv.length());
        }

        return text;
    }

    /*
     * creates formatted div for passed video with passed (optional) thumbnail
     */
    private String makeVideoDiv(String videoUrl, String thumbnailUrl) {
        if (TextUtils.isEmpty(videoUrl))
            return "";

        // sometimes we get src values like "//player.vimeo.com/video/70534716" - prefix these with http:
        if (videoUrl.startsWith("//"))
            videoUrl = "http:" + videoUrl;

        int overlaySz = getResources().getDimensionPixelSize(R.dimen.reader_video_overlay_size) / 2;

        if (TextUtils.isEmpty(thumbnailUrl)) {
            return String.format("<div class='wpreader-video' align='center'><a href='%s'><img style='width:%dpx; height:%dpx; display:block;' src='%s' /></a></div>", videoUrl, overlaySz, overlaySz, OVERLAY_IMG);
        } else {
            return "<div style='position:relative'>"
                    + String.format("<a href='%s'><img src='%s' style='width:100%%; height:auto;' /></a>", videoUrl, thumbnailUrl)
                    + String.format("<a href='%s'><img src='%s' style='width:%dpx; height:%dpx; position:absolute; left:0px; right:0px; top:0px; bottom:0px; margin:auto;'' /></a>", videoUrl, OVERLAY_IMG, overlaySz, overlaySz)
                    + "</div>";
        }
    }

    /*
     * called when user taps an image in the webView - shows the image full-screen
     */
    private void showPhotoViewer(final String imageUrl) {
        if (TextUtils.isEmpty(imageUrl))
            return;
        if (!hasActivity())
            return;
        // images in private posts must use https for auth token to be sent with request
        if (mPost.isPrivate) {
            ReaderActivityLauncher.showReaderPhotoViewer(getActivity(), UrlUtils.makeHttps(imageUrl));
        } else {
            ReaderActivityLauncher.showReaderPhotoViewer(getActivity(), imageUrl);
        }
    }

    /*
     * size to use for images that fit the full width of the listView item
     */
    private int getFullSizeImageWidth() {
        int displayWidth = DisplayUtils.getDisplayPixelWidth(getActivity());
        int marginWidth = getResources().getDimensionPixelOffset(R.dimen.reader_list_margin);
        return displayWidth - (marginWidth * 2);
    }

    /*
     * build html for post's content
     */
    private String getPostHtml(ReaderPost post) {
        if (post == null)
            return "";

        String content;
        if (post.hasText()) {
            content = post.getText();
            // insert video div before content if this is a VideoPress post (video otherwise won't appear)
            if (post.isVideoPress)
                content = makeVideoDiv(post.getFeaturedVideo(), post.getFeaturedImage()) + content;
        } else if (post.hasFeaturedImage()) {
            // some photo blogs have posts with empty content but still have a featured image, so
            // use the featured image as the content
            content = String.format("<p><img class='img.size-full' src='%s' /></p>", post.getFeaturedImage());
        } else {
            content = "";
        }

        int marginLarge = getResources().getDimensionPixelSize(R.dimen.margin_large);
        int marginSmall = getResources().getDimensionPixelSize(R.dimen.margin_small);
        int marginExtraSmall = getResources().getDimensionPixelSize(R.dimen.margin_extra_small);

        final Context context = WordPress.getContext();
        final String linkColor = HtmlUtils.colorResToHtmlColor(context, R.color.reader_hyperlink);
        final String greyLight = HtmlUtils.colorResToHtmlColor(context, R.color.grey_light);
        final String greyExtraLight = HtmlUtils.colorResToHtmlColor(context, R.color.grey_extra_light);

        StringBuilder sbHtml = new StringBuilder("<!DOCTYPE html><html><head><meta charset='UTF-8' />");

        // title isn't strictly necessary, but source is invalid html5 without one
        sbHtml.append("<title>Reader Post</title>");

        // use "Open Sans" Google font
        sbHtml.append("<link rel='stylesheet' type='text/css' href='http://fonts.googleapis.com/css?family=Open+Sans' />");

        sbHtml.append("<style type='text/css'>")
              .append("  body { font-family: 'Open Sans', sans-serif; margin: 0px; padding: 0px; }")
              .append("  body, p, div { font-size: 1em; line-height: 1.5em; max-width: 100% !important;}");

        // add border, background color, and padding to pre blocks, and add overflow scrolling
        // so user can scroll the block if it's wider than the display
        sbHtml.append("  pre { overflow-x: scroll;")
              .append("        border: 1px solid ").append(greyLight).append("; ")
              .append("        background-color: ").append(greyExtraLight).append("; ")
              .append("        padding: ").append(marginSmall).append("px; }");

         // use a consistent top/bottom margin for paragraphs
        sbHtml.append("  p { margin-top: 0px; margin-bottom: ").append(marginSmall).append("px; }");

        // css for video div when no video thumb available (see processVideos)
        sbHtml.append("  div.wpreader-video { background-color: ").append(greyExtraLight).append(";")
              .append("                       width: 100%; padding: ").append(marginLarge).append("px; }");

        // make sure links are shown in the same color they are elsewhere in the app
        sbHtml.append("  a { text-decoration: none; color: ").append(linkColor).append("; }");

        // hide iframes & embeds (they won't work since script is disabled)
        sbHtml.append("  iframe, embed { display: none; }");

        // don't allow any image to be wider than the screen
        sbHtml.append("  img { max-width: 100% !important; height: auto;}");

        // show large wp images full-width (unnecessary in most cases since they'll already be at least
        // as wide as the display, except maybe when viewed on a large landscape tablet)
        sbHtml.append("  img.size-full, img.size-large { display: block; width: 100% !important; height: auto; }");

        // center medium-sized wp image
        sbHtml.append("  img.size-medium { display: block; margin-left: auto !important; margin-right: auto !important; }");

        // hide VideoPress divs that don't make sense on mobile
        sbHtml.append("  div.video-player, div.videopress-title, div.play-button, div.videopress-watermark { display: none; }");

        // tiled image galleries look bad on mobile due to their hard-coded DIV and IMG sizes, so if
        // content contains a tiled image gallery, remove the height params and replace the width
        // params with ones that make images fit the width of the listView item, then adjust the
        // relevant CSS classes so their height/width are auto, and add top/bottom margin to images
        if (content.contains("tiled-gallery-item")) {
            String widthParam = "w=" + Integer.toString(getFullSizeImageWidth());
            content = content.replaceAll("w=[0-9]+", widthParam).replaceAll("h=[0-9]+", "");
            sbHtml.append("  div.gallery-row, div.gallery-group { width: auto !important; height: auto !important; }")
                  .append("  div.tiled-gallery-item img { ")
                  .append("     width: auto !important; height: auto !important;")
                  .append("     margin-top: ").append(marginExtraSmall).append("px; ")
                  .append("     margin-bottom: ").append(marginExtraSmall).append("px; ")
                  .append(" }")
                  .append("  div.tiled-gallery-caption { clear: both; }");
        }

        sbHtml.append("</style></head><body>")
                .append(processVideos(content))
                .append("</body></html>");

        return sbHtml.toString();
    }

    /*
     *  called when the post doesn't exist in local db, need to get it from server
     */
    private void requestPost() {
        final ProgressBar progress = (ProgressBar) getContainerView().findViewById(R.id.progress_loading);
        progress.setVisibility(View.VISIBLE);
        progress.bringToFront();

        ReaderActions.ActionListener actionListener = new ReaderActions.ActionListener() {
            @Override
            public void onActionResult(boolean succeeded) {
                progress.setVisibility(View.GONE);
                if (succeeded) {
                    showPost();
                } else {
                    postFailed();
                }
            }
        };
        ReaderPostActions.requestPost(mBlogId, mPostId, actionListener);
    }

    /*
     * called when post couldn't be loaded and failed to be returned from server
     */
    private void postFailed() {
        if (!hasActivity())
            return;
        ToastUtils.showToast(getActivity(), R.string.reader_toast_err_get_post, ToastUtils.Duration.LONG);
    }

    /*
     * AsyncTask to retrieve & display this post
     */
    @SuppressLint("NewApi")
    private void showPost() {
        if (mIsPostTaskRunning)
            AppLog.w(T.READER, "post task already running");

        if (SysUtils.canUseExecuteOnExecutor()) {
            new ShowPostTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } else {
            new ShowPostTask().execute();
        }
    }
    private boolean mIsPostTaskRunning = false;
    private class ShowPostTask extends AsyncTask<Void, Void, Boolean> {
        TextView txtTitle;
        TextView txtBlogName;
        TextView txtAuthorName;
        TextView txtDate;
        TextView txtFollow;

        ImageView imgBtnReblog;
        ImageView imgBtnComment;

        WPNetworkImageView imgAvatar;
        WPNetworkImageView imgFeatured;

        String postHtml;
        String featuredImageUrl;
        boolean showFeaturedImage;

        @Override
        protected void onPreExecute() {
            mIsPostTaskRunning = true;
        }
        @Override
        protected void onCancelled() {
            mIsPostTaskRunning = false;
        }
        @Override
        protected Boolean doInBackground(Void... params) {
            final ViewGroup container = getContainerView();

            txtTitle = (TextView) container.findViewById(R.id.text_title);
            txtBlogName = (TextView) container.findViewById(R.id.text_blog_name);
            txtDate = (TextView) container.findViewById(R.id.text_date);
            txtFollow = (TextView) container.findViewById(R.id.text_follow);
            txtAuthorName = (TextView) container.findViewById(R.id.text_author_name);

            imgAvatar = (WPNetworkImageView) container.findViewById(R.id.image_avatar);
            imgFeatured = (WPNetworkImageView) container.findViewById(R.id.image_featured);

            imgBtnReblog = (ImageView) mLayoutIcons.findViewById(R.id.image_reblog_btn);
            imgBtnComment = (ImageView) mLayoutIcons.findViewById(R.id.image_comment_btn);

            // retrieve this post - return false if not found
            mPost = ReaderPostTable.getPost(mBlogId, mPostId);
            if (mPost==null)
                return false;

            postHtml = getPostHtml(mPost);

            // detect whether the post has a featured image that's not in the content - if so,
            // it will be shown between the post's title and its content (but skip mshots)
            if (mPost.hasFeaturedImage() && !PhotonUtils.isMshotsUrl(mPost.getFeaturedImage())) {
                Uri uri = Uri.parse(mPost.getFeaturedImage());
                if (!mPost.getText().contains(StringUtils.notNullStr(uri.getLastPathSegment()))) {
                    showFeaturedImage = true;
                    int imgHeight = getResources().getDimensionPixelSize(R.dimen.reader_featured_image_height);
                    featuredImageUrl = mPost.getFeaturedImageForDisplay(0, imgHeight);
                }
            }

            return true;
        }
        @Override
        protected void onPostExecute(Boolean result) {
            mIsPostTaskRunning = false;

            if (!hasActivity())
                return;

            if (!result) {
                /*
                 * post couldn't be loaded, which means it doesn't exist in db - so request it from the server
                 */
                requestPost();
                return;
            }

            // set the activity title to the post's title
            getActivity().setTitle(mPost.getTitle());

            showFollowedStatus(txtFollow, mPost.isFollowedByCurrentUser);

            // if we know refreshLikes() is going to show the liking layout, force it to take up
            // space right now
            if (mPost.numLikes > 0 && mLayoutLikes.getVisibility() == View.GONE)
                mLayoutLikes.setVisibility(View.INVISIBLE);

            if (mPost.hasTitle()) {
                txtTitle.setText(mPost.getTitle());
            } else {
                txtTitle.setText(R.string.reader_untitled_post);
            }

            txtBlogName.setText(mPost.getBlogName());
            txtDate.setText(DateTimeUtils.javaDateToTimeSpan(mPost.getDatePublished()));

            // show author name if it exists and is different than the blog name
            if (mPost.hasAuthorName() && !mPost.getAuthorName().equals(mPost.getBlogName())) {
                txtAuthorName.setText(mPost.getAuthorName());
                txtAuthorName.setVisibility(View.VISIBLE);
            } else {
                txtAuthorName.setVisibility(View.GONE);
            }

            if (mPost.hasPostAvatar()) {
                int avatarSz = getResources().getDimensionPixelSize(R.dimen.avatar_sz_medium);
                imgAvatar.setImageUrl(mPost.getPostAvatarForDisplay(avatarSz), WPNetworkImageView.ImageType.AVATAR);
                imgAvatar.setVisibility(View.VISIBLE);
            } else {
                imgAvatar.setVisibility(View.GONE);
            }

            if (showFeaturedImage) {
                imgFeatured.setVisibility(View.VISIBLE);
                imgFeatured.setImageUrl(featuredImageUrl, WPNetworkImageView.ImageType.PHOTO);
                imgFeatured.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showPhotoViewer(mPost.getFeaturedImage());
                    }
                });
            } else {
                imgFeatured.setVisibility(View.GONE);
            }

            // enable reblogging wp posts
            imgBtnReblog.setVisibility(mPost.isWP() ? View.VISIBLE : View.GONE);
            imgBtnReblog.setSelected(mPost.isRebloggedByCurrentUser);
            if (mPost.isWP()) {
                imgBtnReblog.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        doPostReblog(imgBtnReblog, mPost);
                    }
                });
            }

            // enable adding a comment if comments are open on this post
            if (mPost.isWP() && mPost.isCommentsOpen) {
                imgBtnComment.setVisibility(View.VISIBLE);
                imgBtnComment.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleShowAddCommentBox();
                    }
                });
            } else {
                imgBtnComment.setVisibility(View.GONE);
            }

            // tapping title opens post in browser
            txtTitle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ReaderActivityLauncher.openUrl(getActivity(), mPost.getUrl());
                }
            });

            // tapping blog name, author name or avatar opens blog home page in browser
            if (mPost.hasBlogUrl()) {
                View.OnClickListener clickListener = new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ReaderActivityLauncher.openUrl(getActivity(), mPost.getBlogUrl());
                    }
                };
                txtBlogName.setOnClickListener(clickListener);
                txtAuthorName.setOnClickListener(clickListener);
                imgAvatar.setOnClickListener(clickListener);
            }

            // webView is invisible at design time, don't show it until the page finishes loading so it
            // has time to layout the post before it appears...
            mWebView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    if (mWebView.getVisibility()!=View.VISIBLE)
                        mWebView.setVisibility(View.VISIBLE);
                }
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    // open clicked urls in default browser or else urls will open in this webView,
                    // but only do this when webView has loaded (is visible) - have seen some posts
                    // containing iframes automatically try to open urls (without being clicked)
                    // before the page has loaded
                    if (view.getVisibility() == View.VISIBLE) {
                        ReaderActivityLauncher.openUrl(getActivity(), url);
                        return true;
                    } else {
                        return false;
                    }
                }
            });

            //...but force it to appear after a short delay to ensure user never has to be faced
            // with a blank post for too long (very important on slow connections)
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (mWebView.getVisibility()!=View.VISIBLE) {
                        mWebView.setVisibility(View.VISIBLE);
                        AppLog.w(T.READER, "forced webView to appear before page finished");
                    }
                }
            }, WEBVIEW_DELAY_MS);

            // IMPORTANT: must use loadDataWithBaseURL() rather than loadData() since the latter often fails
            // https://code.google.com/p/android/issues/detail?id=4401
            mWebView.loadDataWithBaseURL(null, postHtml, "text/html", "UTF-8", null);

            // only show action buttons for WP posts
            mLayoutIcons.setVisibility(mPost.isWP() ? View.VISIBLE : View.GONE);

            // make sure the adapter is assigned now that we've retrieved the post and updated views
            if (!hasCommentAdapter())
                getListView().setAdapter(getCommentAdapter());

            refreshLikes(false);
            refreshComments();

            // get the latest info for this post if we haven't updated it already
            if (!mHasAlreadyUpdatedPost) {
                updatePost();
                mHasAlreadyUpdatedPost = true;
            }

            // show listView now that post is loaded
            getListView().setVisibility(View.VISIBLE);
        }
    }
}
