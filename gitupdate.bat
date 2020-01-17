git config --global core.autocrlf true
git pull origin master
git add -A
git commit -m "v4.8.8"
git push origin master
git tag -a v4.8.8 -m "release v4.8.8"
git push origin --tags
pause